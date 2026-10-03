package com.hmdp.upgrade;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.flywaydb.core.Flyway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="UPGRADE_INTEGRATION",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MiddlewareTest {
    JdbcTemplate db;Trading trading;Transactions tx;StringRedisTemplate redis;
    LettuceConnectionFactory lettuce;ShopCache cache;KafkaTemplate<String,String> kafka;Delivery delivery;Admission gate;BenchmarkReservation reservations;Reservations productionReservations;
    DefaultKafkaProducerFactory<String,String> producer;
    long sequence=System.currentTimeMillis();
    long next() { return ++sequence; }
    @BeforeAll void setup() {
        var source=new DriverManagerDataSource(System.getenv().getOrDefault("UPGRADE_TEST_DB_URL","jdbc:mysql://127.0.0.1:23307/life_choice_verification?connectionTimeZone=UTC"),System.getenv().getOrDefault("DATABASE_USERNAME","life_choice"),System.getenv("UPGRADE_DB_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        db=new JdbcTemplate(source);tx=new Transactions(new DataSourceTransactionManager(source));trading=new Trading(db,tx);
        var config=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT","26380")));config.setPassword(System.getenv("UPGRADE_REDIS_PASSWORD"));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("UPGRADE_TEST_REDIS_DB","0")));
        lettuce=new LettuceConnectionFactory(config);lettuce.afterPropertiesSet();lettuce.start();redis=new StringRedisTemplate(lettuce);
        cache=new ShopCache(db,tx,trading,redis,new ObjectMapper(),80,250,false);
        gate=new Admission(redis,db,2,10,5000,60);
        reservations=new BenchmarkReservation(redis);
        productionReservations=new Reservations(redis,db,trading,tx);
        producer=new DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers",System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:29093"),"acks","all",
            "enable.idempotence",true,"key.serializer",StringSerializer.class,"value.serializer",StringSerializer.class));
        kafka=new KafkaTemplate<>(producer);
        delivery=new Delivery(db,tx,trading,kafka,new ObjectMapper(),gate,productionReservations,System.getenv().getOrDefault("UPGRADE_TOPIC","life-choice-orders-v1"),false);
    }
    @AfterAll void close() { cache.close();producer.destroy();lettuce.destroy(); }
    long activity(int stock) {
        long id=next();Instant now=Instant.now();
        db.update("INSERT INTO ux_activity VALUES(?,?,?,?,?,?,?)",id,stock,stock,990,Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(3600)),Timestamp.from(now.plusSeconds(3700)));return id;
    }
    Trading.Event event(Trading.Request r) {
        return db.queryForObject("SELECT * FROM ux_outbox WHERE request_id=?",(s,n)->new Trading.Event(1,s.getString("id"),r.id(),r.activityId()),r.id());
    }
    void valid() { assertEquals(0L,trading.invariants().get("stockViolations"));assertEquals(0L,trading.invariants().get("stateViolations")); }
    @Test void mysqlRedisDelayedCloseAndSandboxRefundRecoverFromLostQueue() {
        var local=new Trading(db,tx);
        org.springframework.test.util.ReflectionTestUtils.setField(local,"closeTasks",new CloseTasks(db));
        var closer=new DelayedClose(db,local,redis);String queue="ux:test:close:"+next();
        org.springframework.test.util.ReflectionTestUtils.setField(closer,"key",queue);
        var payments=new Payments(db,tx,local);long user=next(),activity=activity(2);
        try {
            String r=local.accept(user,"payment_"+user,activity,true).id();
            String p=(String)payments.create(user,r).get("id");
            closer.enqueue();assertNotNull(redis.opsForZSet().score(queue,r));
            var past=Timestamp.from(Instant.now().minusSeconds(1));
            db.update("UPDATE ux_order SET confirm_until=? WHERE request_id=?",past,r);
            db.update("UPDATE ux_close_task SET due_at=? WHERE request_id=?",past,r);
            redis.opsForZSet().add(queue,r,past.getTime());closer.drain();closer.drain();
            assertEquals("EXPIRED",local.order(user,r).get("state"));assertNull(redis.opsForZSet().score(queue,r));
            payments.received(user,p,"mysql_receipt_"+user,990);payments.refunds();payments.refunds();
            assertEquals("REFUNDED",payments.get(user,p).get("state"));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_sandbox_refund WHERE channel_id=?",Integer.class,"mysql_receipt_"+user));
            String r2=local.accept(user+1,"payment_"+(user+1),activity,true).id();closer.enqueue();redis.delete(queue);
            db.update("UPDATE ux_order SET confirm_until=? WHERE request_id=?",past,r2);
            db.update("UPDATE ux_close_task SET due_at=? WHERE request_id=?",past,r2);
            new DelayedClose(db,local,redis).reconcile();
            assertEquals("EXPIRED",local.order(user+1,r2).get("state"));valid();
        } finally {redis.delete(queue);}
    }
    @Test void bloomCrossInstanceRegistrationAndJournalLossFailOpen() {
        var first=new ShopBloom(db,redis,tx);var second=new ShopBloom(db,redis,tx);
        first.rebuild();second.rebuild();long id=next();
        assertFalse(second.mightContain(id));
        tx.run(()->{first.beforeInsert(id);db.update("INSERT INTO ux_shop(id,name,description,revision) VALUES(?,?,?,1)",id,"Bloom test","new merchant");return null;});
        assertTrue(second.mightContain(id));
        redis.delete(ShopBloom.ADDED);
        assertTrue(second.mightContain(next()));assertEquals(0L,second.metrics().get("ready"));
    }
    @Test void bloomRebuildWaitsForInsertAcrossJournalReset() throws Exception {
        var writer=new ShopBloom(db,redis,tx);var reader=new ShopBloom(db,redis,tx);
        writer.rebuild();long id=next();var registered=new CountDownLatch(1);var commit=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var insert=pool.submit(()->tx.run(()->{
                writer.beforeInsert(id);registered.countDown();
                try {if(!commit.await(3,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                db.update("INSERT INTO ux_shop(id,name,description,revision) VALUES(?,?,?,1)",id,"concurrent merchant","test");return null;
            }));
            assertTrue(registered.await(3,TimeUnit.SECONDS));redis.delete(ShopBloom.ADDED);
            var rebuild=pool.submit(reader::rebuild);
            assertThrows(TimeoutException.class,()->rebuild.get(100,TimeUnit.MILLISECONDS));
            commit.countDown();insert.get(5,TimeUnit.SECONDS);rebuild.get(5,TimeUnit.SECONDS);
            assertEquals(1L,reader.metrics().get("ready"));assertTrue(reader.mightContain(id));
        } finally {commit.countDown();pool.shutdownNow();}
    }
    @Test void mysqlProjectionRetryAndOldRevisionCannotRegressCancelledOrder() {
        var projection=new OrderProjection(db);var service=new Trading(db,tx);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"projection",projection);
        long user=next(),a=activity(1);var request=service.accept(user,"projection_"+a,a,true);
        var old=db.queryForMap("SELECT o.*,p.revision FROM ux_order o JOIN ux_order_projection p ON o.id=p.order_id WHERE o.request_id=?",request.id());
        projection.apply(old);service.transition(user,request.id(),"cancel");
        var latest=db.queryForMap("SELECT o.*,p.revision FROM ux_order o JOIN ux_order_projection p ON o.id=p.order_id WHERE o.request_id=?",request.id());
        projection.apply(latest);projection.apply(old);projection.apply(latest);
        assertEquals("CANCELLED",projection.list(user,1).get(0).state());
        assertTrue(projection.list(user+32,1).stream().noneMatch(r->r.requestId().equals(request.id())));valid();
    }
    @Test void mysqlThousandRequestsHundredStock() throws Exception {
        long a=activity(100);var pool=Executors.newFixedThreadPool(16);
        try {
            var tasks=new ArrayList<Callable<String>>();
            for(int i=1;i<=1000;i++) { long user=i;tasks.add(()->trading.accept(user,"mysql_"+a+"_"+user,a,true).state()); }
            int successes=0;for(var f:pool.invokeAll(tasks)) if(f.get().equals("SUCCEEDED")) successes++;
            assertEquals(100,successes);valid();
        } finally { pool.shutdownNow(); }
    }
    @Test void mysqlUniqueUserRaceAndReplay() throws Exception {
        long a=activity(10);var pool=Executors.newFixedThreadPool(8);
        try {
            var tasks=new ArrayList<Callable<String>>();
            for(int i=0;i<20;i++) { int key=i;tasks.add(()->trading.accept(99,"unique_"+a+"_"+key,a,true).state()); }
            int successes=0;for(var f:pool.invokeAll(tasks)) if(f.get().equals("SUCCEEDED")) successes++;
            assertEquals(1,successes);valid();
        } finally { pool.shutdownNow(); }
    }
    @Test void mysqlRollbackAfterStockUpdate() {
        long a=activity(1);
        assertThrows(RuntimeException.class,()->tx.run(()->{db.update("UPDATE ux_activity SET available=available-1 WHERE id=?",a);throw new RuntimeException("injected");}));valid();
    }
    @Test void mysqlOrderInsertFailureRollsBackActualConsumerTransaction() {
        long a=activity(1);var r=trading.accept(7,"insert_failure_"+a,a,false);
        JdbcTemplate failing=new JdbcTemplate(db.getDataSource()) {
            @Override public int update(String sql,Object... args) {
                if(sql.startsWith("INSERT INTO ux_order")) throw new org.springframework.dao.DataIntegrityViolationException("injected insert failure");
                return super.update(sql,args);
            }
        };
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new Trading(failing,tx).process(event(r)));
        assertEquals("ACCEPTED",trading.result(7,r.id()).state());valid();
        trading.process(event(r));assertEquals("SUCCEEDED",trading.result(7,r.id()).state());valid();
    }
    @Test void mysqlTimeoutThenLateConsumer() {
        long a=activity(1);var r=trading.accept(7,"late_"+a,a,false);
        db.update("UPDATE ux_request SET deadline=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),r.id());
        trading.expireRequests();trading.process(event(r));assertEquals("EXPIRED",trading.result(7,r.id()).state());valid();
    }
    @Test void mysqlConcurrentConfirmCancelExpire() throws Exception {
        long a=activity(1);var r=trading.accept(7,"cancel_"+a,a,true);
        var pool=Executors.newFixedThreadPool(3);
        try { for(var f:pool.invokeAll(List.<Callable<Object>>of(()->trading.transition(7,r.id(),"confirm"),()->trading.transition(7,r.id(),"cancel"),()->trading.transition(7,r.id(),"expire")))) f.get();valid(); }
        finally { pool.shutdownNow(); }
    }
    @Test void redisLuaLimitIsAtomic() throws Exception {
        long a=activity(100);int admitted=0;
        for(int i=0;i<3;i++) try { gate.enter(1,a);admitted++;gate.leave(); } catch(Problem p) { assertEquals(429,p.status); }
        assertEquals(2,admitted);
    }
    @Test void redisReservationFiltersBeforeMysqlAndIsIdempotent() throws Exception {
        long a=activity(100);reservations.prepare(a,100);var pool=Executors.newFixedThreadPool(16);
        try {
            var tasks=new ArrayList<Callable<BenchmarkReservation.Decision>>();
            for(int i=1;i<=1000;i++) { long user=i;tasks.add(()->reservations.reserve(a,user,"reserve_"+a+"_"+user)); }
            int admitted=0;for(var f:pool.invokeAll(tasks)) if(f.get()==BenchmarkReservation.Decision.ADMITTED) admitted++;
            assertEquals(100,admitted);assertEquals(0,reservations.remaining(a));
            assertEquals(BenchmarkReservation.Decision.ADMITTED,reservations.reserve(a,1,"reserve_"+a+"_1"));
            assertEquals(BenchmarkReservation.Decision.ALREADY_PURCHASED,reservations.reserve(a,1,"reserve_"+a+"_other"));
            assertEquals(BenchmarkReservation.Decision.SOLD_OUT,reservations.reserve(a,1001,"reserve_"+a+"_1001"));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_request WHERE activity_id=?",Integer.class,a));
        } finally { pool.shutdownNow(); }
    }
    @Test void repairOrdersByDueTimeAndSkipsFutureRetries() {
        long a=activity(3);productionReservations.prepare(a,3);
        var requests=new java.util.ArrayList<Trading.Request>();
        for(int i=0;i<3;i++) {
            String key="due_order_"+a+"_"+i;
            productionReservations.reserve(a,800+i,key,Instant.now().plusSeconds(5));
            requests.add(trading.accept(800+i,key,a,true));
        }
        Instant now=Instant.now();
        db.update("UPDATE ux_reservation_action SET next_at=? WHERE request_id=?",Timestamp.from(now.plusSeconds(3600)),requests.get(0).id());
        db.update("UPDATE ux_reservation_action SET next_at=?,created_at=? WHERE request_id=?",Timestamp.from(now.minusSeconds(172800)),Timestamp.from(now.minusSeconds(172800)),requests.get(1).id());
        db.update("UPDATE ux_reservation_action SET next_at=?,created_at=? WHERE request_id=?",Timestamp.from(now.minusSeconds(259200)),Timestamp.from(now.minusSeconds(86400)),requests.get(2).id());
        org.springframework.test.util.ReflectionTestUtils.setField(productionReservations,"repairBatch",1);
        try {
            assertEquals(1,productionReservations.drainActions());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE request_id=? AND applied_at IS NOT NULL",Integer.class,requests.get(2).id()));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE request_id IN (?,?) AND applied_at IS NOT NULL",Integer.class,requests.get(0).id(),requests.get(1).id()));
            assertEquals(1,productionReservations.drainActions());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE request_id=? AND applied_at IS NOT NULL",Integer.class,requests.get(0).id()));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(productionReservations,"repairBatch",100);
            db.update("UPDATE ux_reservation_action SET next_at=CURRENT_TIMESTAMP(3) WHERE request_id=?",requests.get(0).id());
            productionReservations.drainActions();
        }
        assertEquals(0,productionReservations.remaining(a));valid();
    }
    @Test void delayedConfirmAfterRestoreDoesNotConsumeStockAgain() {
        long a=activity(1);String key="restore_first_"+a;productionReservations.prepare(a,1);
        productionReservations.reserve(a,811,key,Instant.now().plusSeconds(5));
        var request=trading.accept(811,key,a,true);trading.transition(811,request.id(),"cancel");
        Instant now=Instant.now();
        db.update("UPDATE ux_reservation_action SET next_at=? WHERE request_id=? AND action='CONFIRM'",Timestamp.from(now.minusSeconds(30)),request.id());
        db.update("UPDATE ux_reservation_action SET next_at=? WHERE request_id=? AND action='RESTORE'",Timestamp.from(now.minusSeconds(60)),request.id());
        org.springframework.test.util.ReflectionTestUtils.setField(productionReservations,"repairBatch",1);
        try {
            assertEquals(1,productionReservations.drainActions());
            assertEquals("4",productionReservations.state(a,811,key));assertEquals(1,productionReservations.remaining(a));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE request_id=? AND action='CONFIRM' AND applied_at IS NOT NULL",Integer.class,request.id()));
            productionReservations.drainActions();
            assertEquals("4",productionReservations.state(a,811,key));assertEquals(1,productionReservations.remaining(a));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(productionReservations,"repairBatch",100);productionReservations.drainActions();
        }
        valid();
    }
    @Test void productionReservationConfirmAndCancelRestoreAreIdempotent() {
        long a=activity(1);String key="production_"+a;productionReservations.prepare(a,1);
        assertEquals(Reservations.Decision.ADMITTED,productionReservations.reserve(a,77,key,Instant.now().plusSeconds(5)).decision());
        assertEquals(0,productionReservations.remaining(a));
        var request=trading.accept(77,key,a,true);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE request_id=? AND action='CONFIRM'",Integer.class,request.id()));
        productionReservations.drainActions();productionReservations.drainActions();
        assertEquals("2",productionReservations.state(a,77,key));assertEquals(0,productionReservations.remaining(a));
        trading.transition(77,request.id(),"cancel");productionReservations.drainActions();productionReservations.drainActions();
        assertEquals("4",productionReservations.state(a,77,key));assertEquals(1,productionReservations.remaining(a));
        assertEquals(Reservations.Decision.ALREADY_PURCHASED,productionReservations.reserve(a,77,key+"_new",Instant.now().plusSeconds(5)).decision());
        valid();
    }
    int fullReconciliationSweep() {
        long initial=((Number)productionReservations.settings().get("completedSweeps")).longValue();int repaired=0;
        int limit=db.queryForObject("SELECT COUNT(*) FROM ux_reservation_epoch",Integer.class)+2;
        for(int i=0;i<limit;i++) {
            repaired+=productionReservations.reconcileStale();
            if(((Number)productionReservations.settings().get("completedSweeps")).longValue()>initial) return repaired;
        }
        throw new AssertionError("Sweep did not finish within activity count bound");
    }
    @Test void orphanReservationIsReleasedByReconciliation() throws Exception {
        long a=activity(1);String key="orphan_"+a;productionReservations.prepare(a,1);
        productionReservations.reserve(a,88,key,Instant.now().minusSeconds(10));
        assertEquals(0,productionReservations.remaining(a));
        fullReconciliationSweep();fullReconciliationSweep();
        assertEquals(1,productionReservations.remaining(a));assertEquals("3",productionReservations.state(a,88,key));
        assertEquals(Reservations.Decision.ADMITTED,productionReservations.reserve(a,88,key+"_retry",Instant.now().plusSeconds(5)).decision());
    }
    @Test void acceptedRequestIsNotMistakenForOrphan() {
        long a=activity(1);String key="accepted_"+a;productionReservations.prepare(a,1);
        productionReservations.reserve(a,99,key,Instant.now().minusSeconds(10));
        var request=trading.accept(99,key,a,false);
        fullReconciliationSweep();fullReconciliationSweep();assertEquals("1",productionReservations.state(a,99,key));
        trading.process(event(request));productionReservations.drainActions();
        assertEquals("2",productionReservations.state(a,99,key));valid();
    }
    @Test void redisSoldHintReleaseIsDurable() {
        long a=activity(1);var r=trading.accept(7,"hint_"+a,a,true);
        gate.soldOut(a);assertTrue(Boolean.TRUE.equals(redis.hasKey("ux:sold:{"+a+"}")));
        trading.transition(7,r.id(),"cancel");cache.drainInvalidations();assertFalse(Boolean.TRUE.equals(redis.hasKey("ux:sold:{"+a+"}")));valid();
    }
    @Test void redisColdBurstNegativeAndCreate() throws Exception {
        long id=next();assertNull(cache.get(id));cache.save(id,"created","new description");
        redis.delete(ShopCache.key(id));long before=cache.metrics().get("databaseReads");
        var pool=Executors.newFixedThreadPool(16);
        try {
            var tasks=new ArrayList<Callable<ShopCache.Shop>>();for(int i=0;i<16;i++) tasks.add(()->cache.get(id));
            for(var f:pool.invokeAll(tasks)) assertEquals("created",f.get().name());
            assertEquals(1,cache.metrics().get("databaseReads")-before);
        } finally { pool.shutdownNow(); }
    }
    @Test void redisSoftAndHardExpiry() throws Exception {
        long id=next();cache.save(id,"ttl","original");cache.get(id);Thread.sleep(110);
        assertEquals("ttl",cache.get(id).name());assertTrue(cache.metrics().get("staleHits")>0);
        Thread.sleep(350);assertEquals("ttl",cache.get(id).name());
    }
    @Test void redisOldOwnerCannotFillOrUnlockNewOwner() {
        String key=ShopCache.key(next());redis.opsForValue().set(key+":lock","new-owner");
        assertEquals(0L,redis.execute(ShopCache.FILL,List.of(key+":lock",key),"old-owner","stale","10000"));
        assertEquals(0L,redis.execute(ShopCache.RELEASE,List.of(key+":lock"),"old-owner"));
        assertEquals("new-owner",redis.opsForValue().get(key+":lock"));assertNull(redis.opsForValue().get(key));redis.delete(key+":lock");
    }
    @Test void redisLeaseExpiryHandsOffToNewWorker() throws Exception {
        String key=ShopCache.key(next());redis.opsForValue().set(key+":lock","expired",Duration.ofMillis(60));Thread.sleep(100);
        assertTrue(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key+":lock","current",Duration.ofSeconds(1))));
        assertEquals(0L,redis.execute(ShopCache.FILL,List.of(key+":lock",key),"expired","stale","1000"));
        assertEquals(1L,redis.execute(ShopCache.FILL,List.of(key+":lock",key),"current","fresh","1000"));
        assertEquals("fresh",redis.opsForValue().get(key));redis.delete(key);
    }
    @Test void saturatedRebuildQueueReturnsBoundedStaleAndReleasesRejectedOwners() throws Exception {
        var local=new ShopCache(db,tx,trading,redis,new ObjectMapper(),80,5000,false);
        var executor=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(local,"rebuild");
        var release=new CountDownLatch(1);
        Runnable blocker=()->{try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        try {
            for(int i=0;i<18;i++) executor.execute(blocker);
            long id=next();String key=ShopCache.key(id);
            redis.opsForValue().set(key,new ObjectMapper().writeValueAsString(new ShopCache.Entry(new ShopCache.Shop(id,"stale","bounded",1),0)),Duration.ofSeconds(2));
            assertEquals("stale",local.get(id).name());
            assertEquals(1,local.metrics().get("rebuildRejected"));assertFalse(Boolean.TRUE.equals(redis.hasKey(key+":lock")));
            assertEquals(16,executor.getQueue().size());
        } finally { release.countDown();local.close(); }
    }
    @Test void databaseReadSlotsRejectWithoutUnboundedFallback() throws Exception {
        var slots=(Semaphore)org.springframework.test.util.ReflectionTestUtils.getField(cache,"readSlots");slots.acquire(4);
        try { assertEquals(503,assertThrows(Problem.class,()->cache.direct(next())).status); }
        finally { slots.release(4); }
    }
    @Test void stalledOwnerColdMissHasBoundedWait() {
        long id=next();String key=ShopCache.key(id);redis.opsForValue().set(key+":lock","slow-reader",Duration.ofSeconds(2));
        long started=System.nanoTime();assertEquals(503,assertThrows(Problem.class,()->cache.get(id)).status);
        assertTrue(System.nanoTime()-started<1_000_000_000L);assertEquals("slow-reader",redis.opsForValue().get(key+":lock"));redis.delete(key+":lock");
    }
    @Test void backlogAndAgeProtectDatabaseAdmission() {
        long a=activity(5);var r=trading.accept(7,"backlog_"+a,a,false);
        var full=new Admission(redis,db,100,100,1,60);
        assertEquals("BACKLOG_FULL",assertThrows(Problem.class,()->full.enter(8,a)).code);
        db.update("UPDATE ux_request SET created_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(120)),r.id());
        var old=new Admission(redis,db,100,100,1000000,60);
        assertEquals("BACKLOG_TOO_OLD",assertThrows(Problem.class,()->old.enter(9,a)).code);trading.process(event(r));valid();
    }
    @Test void originalLuaReturnsIdWithoutCreatingDeliveryRecord() throws Exception {
        long id=next();String stock="seckill:stock:"+id,users="seckill:order:"+id;
        redis.opsForValue().set(stock,"1");
        try {
            String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/baseline/original-seckill.lua"));
            var original=new org.springframework.data.redis.core.script.DefaultRedisScript<>(source,Long.class);
            long streamBefore=Boolean.TRUE.equals(redis.hasKey("stream.orders"))?redis.opsForStream().size("stream.orders"):0;
            assertEquals(0L,redis.execute(original,List.of(),""+id,"123","456"));
            assertEquals("0",redis.opsForValue().get(stock));
            long streamAfter=Boolean.TRUE.equals(redis.hasKey("stream.orders"))?redis.opsForStream().size("stream.orders"):0;
            assertEquals(streamBefore,streamAfter);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_order WHERE activity_id=?",Integer.class,id));
        } finally {redis.delete(List.of(stock,users));}
    }
    @Test void invalidationFencesOldReadAndGeneration() {
        long id=next();cache.save(id,"old","old");String key=ShopCache.key(id);
        redis.opsForValue().set(key+":lock","stale-owner");cache.save(id,"new","new");
        assertEquals(0L,redis.execute(ShopCache.FILL,List.of(key+":lock",key),"stale-owner","old","10000"));
        assertEquals("new",cache.get(id).name());
        tx.run(()->{trading.invalidate(key);return null;});String gen=db.queryForObject("SELECT generation FROM ux_invalidation WHERE cache_key=?",String.class,key);
        tx.run(()->{trading.invalidate(key);return null;});
        assertEquals(0,db.update("DELETE FROM ux_invalidation WHERE cache_key=? AND generation=?",key,gen));cache.drainInvalidations();
    }
    @Test void outboxOwnerLeaseCannotAcknowledgeAnotherWorker() {
        long a=activity(1);var r=trading.accept(7,"lease_"+a,a,false);var e=event(r);
        db.update("UPDATE ux_outbox SET owner='new',lease_until=? WHERE id=?",Timestamp.from(Instant.now().plusSeconds(10)),e.eventId());
        assertFalse(delivery.acknowledge(e.eventId(),"old"));assertTrue(delivery.acknowledge(e.eventId(),"new"));
        trading.process(e);valid();
    }
    @Test void operationalReplayPreservesEventIdentityAndAuditsActor() {
        long a=activity(1);var r=trading.accept(7,"replay_"+a,a,false);var e=event(r);trading.process(e);
        db.update("UPDATE ux_outbox SET sent_at=CURRENT_TIMESTAMP(3) WHERE id=?",e.eventId());
        delivery.replay(88,e.eventId());
        assertNull(db.queryForObject("SELECT sent_at FROM ux_outbox WHERE id=?",Timestamp.class,e.eventId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_audit WHERE actor=88 AND target=?",Integer.class,e.eventId()));
        trading.process(e);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_order WHERE activity_id=?",Integer.class,a));valid();
    }
    @Test void realKafkaDuplicateMessagesCommitAfterDatabase() throws Exception {
        String topic="upgrade-it-"+next();long a=activity(1);var r=trading.accept(7,"kafka_"+a,a,false);var e=event(r);
        try(var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:29093"),"group.id",topic,
            "auto.offset.reset","earliest","enable.auto.commit",false,"key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            String body=new ObjectMapper().writeValueAsString(e);
            kafka.send(topic,""+a,body).get(15,TimeUnit.SECONDS);kafka.send(topic,""+a,body).get(15,TimeUnit.SECONDS);
            int received=0;long deadline=System.nanoTime()+20_000_000_000L;
            while(received<2 && System.nanoTime()<deadline) for(var record:consumer.poll(Duration.ofMillis(300))) {
                delivery.consume(record,()->consumer.commitSync());received++;
            }
            assertEquals(2,received);assertEquals("SUCCEEDED",trading.result(7,r.id()).state());valid();
        }
    }
    @Test void poisonPersistsBeforeAcknowledgement() throws Exception {
        long offset=next();boolean[] acknowledged={false};
        delivery.consume(new ConsumerRecord<>("malformed-tests",0,offset,"1","not-json"),()->acknowledged[0]=true);
        assertTrue(acknowledged[0]);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_poison WHERE topic='malformed-tests' AND offset_id=?",Integer.class,offset));
    }
}
