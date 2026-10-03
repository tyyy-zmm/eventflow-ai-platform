package com.hmdp.upgrade;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="UPGRADE_RELIABILITY_INTEGRATION",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReliabilityIntegrationTest {
    JdbcTemplate db;Trading trading;Transactions tx;StringRedisTemplate redis;
    LettuceConnectionFactory lettuce;ShopCache cache;Reservations productionReservations;
    long sequence=System.currentTimeMillis();
    long next() { return ++sequence; }
    @BeforeAll void setup() {
        String url=System.getenv("UPGRADE_TEST_DB_URL");
        var source=url==null?new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa",""):
            new DriverManagerDataSource(url,System.getenv().getOrDefault("DATABASE_USERNAME","life_choice"),System.getenv("UPGRADE_DB_PASSWORD"));
        if(url==null) new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__upgrade.sql"),
            new ClassPathResource("db/migration/V3__reservation_actions.sql"),new ClassPathResource("db/migration/V7__reservation_epoch.sql")).execute(source);
        else org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
        db=new JdbcTemplate(source);tx=new Transactions(new DataSourceTransactionManager(source));trading=new Trading(db,tx);
        var config=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT","26380")));config.setPassword(System.getenv("UPGRADE_REDIS_PASSWORD"));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("UPGRADE_TEST_REDIS_DB","14")));
        lettuce=new LettuceConnectionFactory(config);lettuce.afterPropertiesSet();lettuce.start();redis=new StringRedisTemplate(lettuce);
        cache=new ShopCache(db,tx,trading,redis,new ObjectMapper(),80,250,false);
        productionReservations=new Reservations(redis,db,trading,tx);
    }
    @AfterAll void close() { if(cache!=null) cache.close();if(lettuce!=null) lettuce.destroy(); }
    long activity(int stock) {
        long id=next();Instant now=Instant.now();
        db.update("INSERT INTO ux_activity VALUES(?,?,?,?,?,?,?)",id,stock,stock,990,Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(3600)),Timestamp.from(now.plusSeconds(3700)));return id;
    }
    Trading.Event event(Trading.Request r) {
        return db.queryForObject("SELECT * FROM ux_outbox WHERE request_id=?",(s,n)->new Trading.Event(1,s.getString("id"),r.id(),r.activityId()),r.id());
    }
    void valid() { assertEquals(0L,trading.invariants().get("stockViolations"));assertEquals(0L,trading.invariants().get("stateViolations")); }
    @Test @SuppressWarnings("unchecked") void activityLockQueryTimerIncludesActualContention() throws Exception {
        long a=activity(1);var telemetry=new PipelineMetrics();
        var observed=new Trading(db,tx);
        org.springframework.test.util.ReflectionTestUtils.setField(observed,"telemetry",telemetry);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var holder=pool.submit(()->tx.run(()->{
                db.queryForObject("SELECT available FROM ux_activity WHERE id=? FOR UPDATE",Integer.class,a);locked.countDown();
                try {if(!release.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");}
                catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return null;
            }));
            assertTrue(locked.await(2,TimeUnit.SECONDS));
            var waiting=pool.submit(()->{attempting.countDown();return observed.accept(701,"lock_probe_"+a,a,true);});
            assertTrue(attempting.await(2,TimeUnit.SECONDS));Thread.sleep(200);assertFalse(waiting.isDone());
            release.countDown();holder.get(2,TimeUnit.SECONDS);assertEquals("SUCCEEDED",waiting.get(2,TimeUnit.SECONDS).state());
            var stage=(Map<String,Object>)((Map<String,Object>)telemetry.snapshot().get("stages")).get("ACCEPT_LOCK_QUERY");
            assertEquals(1L,stage.get("count"));assertTrue(((Number)stage.get("sumMs")).doubleValue()>=150);valid();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void recoveryFencesOldEpochAndRebuildsCancelledPurchaser() {
        long a=activity(3);productionReservations.prepare(a,3);
        var first=productionReservations.reserve(a,301,"recovery_first_"+a,Instant.now().plusSeconds(5));
        var order=trading.acceptReserved(301,"recovery_first_"+a,a,true,first.epoch());
        trading.transition(301,order.id(),"cancel");
        var old=productionReservations.reserve(a,302,"recovery_late_"+a,Instant.now().plusSeconds(5));
        var pending=productionReservations.reserve(a,303,"recovery_pending_"+a,Instant.now().plusSeconds(5));
        var request=trading.acceptReserved(303,"recovery_pending_"+a,a,false,pending.epoch());
        String epoch=productionReservations.recover(a,900);
        assertNotEquals(old.epoch(),epoch);assertEquals(3,productionReservations.remaining(a));
        assertEquals("EXPIRED",trading.result(303,request.id()).state());
        assertEquals("EXPIRED",trading.process(event(request)).state());
        assertEquals("RESERVATION_EPOCH_CHANGED",assertThrows(Problem.class,
            ()->trading.acceptReserved(302,"recovery_late_"+a,a,true,old.epoch())).code);
        assertEquals(Reservations.Decision.ALREADY_PURCHASED,
            productionReservations.reserve(a,301,"recovery_repeat_"+a,Instant.now().plusSeconds(5)).decision());
        productionReservations.drainActions();assertEquals(3,productionReservations.remaining(a));valid();
    }
    @Test void missingRedisStateFailsClosedAndActionRemainsRetryable() {
        long a=activity(1);productionReservations.prepare(a,1);
        var reserved=productionReservations.reserve(a,401,"missing_state_"+a,Instant.now().plusSeconds(5));
        var request=trading.acceptReserved(401,"missing_state_"+a,a,true,reserved.epoch());
        redis.delete("ux:reserve:{"+a+"}:stock");
        assertEquals("RESERVATION_RECOVERY_REQUIRED",assertThrows(Problem.class,
            ()->productionReservations.reserve(a,402,"missing_retry_"+a,Instant.now().plusSeconds(5))).code);
        productionReservations.drainActions();
        assertNull(db.queryForObject("SELECT applied_at FROM ux_reservation_action WHERE request_id=?",Timestamp.class,request.id()));
        productionReservations.recover(a,900);
        db.update("UPDATE ux_reservation_action SET next_at=CURRENT_TIMESTAMP(3) WHERE request_id=?",request.id());
        productionReservations.drainActions();
        assertNotNull(db.queryForObject("SELECT applied_at FROM ux_reservation_action WHERE request_id=?",Timestamp.class,request.id()));
        assertEquals(0,productionReservations.remaining(a));valid();
    }
    @Test void recoveryRollbackLeavesRedisPausedUntilExplicitRetry() {
        long a=activity(2);productionReservations.prepare(a,2);
        var reservation=productionReservations.reserve(a,501,"rollback_"+a,Instant.now().plusSeconds(5));
        var request=trading.acceptReserved(501,"rollback_"+a,a,false,reservation.epoch());
        var failing=new JdbcTemplate(db.getDataSource()) {
            @Override public int update(String sql,Object... args) {
                if(sql.startsWith("INSERT INTO ux_audit")) throw new IllegalStateException("failure after Redis snapshot");
                return super.update(sql,args);
            }
        };
        var recovery=new Reservations(redis,failing,trading,tx);
        assertThrows(IllegalStateException.class,()->recovery.recover(a,900));
        assertEquals("ACCEPTED",trading.result(501,request.id()).state());
        assertEquals(reservation.epoch(),db.queryForObject("SELECT epoch FROM ux_reservation_epoch WHERE activity_id=?",String.class,a));
        assertEquals(503,assertThrows(Problem.class,
            ()->productionReservations.reserve(a,502,"paused_"+a,Instant.now().plusSeconds(5))).status);
        productionReservations.recover(a,900);
        assertEquals("EXPIRED",trading.result(501,request.id()).state());
        assertEquals(2,productionReservations.remaining(a));valid();
    }
    @Test void recoveryUnknownCommitDoesNotActivateRedis() {
        long a=activity(2);productionReservations.prepare(a,2);
        var ambiguous=new Transactions(new DataSourceTransactionManager(db.getDataSource())) {
            @Override public <T> T run(java.util.function.Supplier<T> action) {
                super.run(action);
                throw new org.springframework.dao.DataAccessResourceFailureException("simulated lost commit response");
            }
        };
        var recovery=new Reservations(redis,db,trading,ambiguous);
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->recovery.recover(a,900));
        assertTrue(redis.opsForValue().get("ux:reserve:{"+a+"}:epoch").startsWith("!"));
        assertEquals(503,assertThrows(Problem.class,
            ()->productionReservations.reserve(a,601,"unknown_"+a,Instant.now().plusSeconds(5))).status);
        productionReservations.recover(a,900);
        var recovered=productionReservations.reserve(a,601,"recovered_"+a,Instant.now().plusSeconds(5));
        assertEquals(Reservations.Decision.ADMITTED,recovered.decision());
        productionReservations.releaseNow(a,601,"recovered_"+a,recovered.epoch());
        valid();
    }
    @Test void twoCacheInstancesReceiveInvalidationAndMissedNoticeExpires() throws Exception {
        var first=new ShopCache(db,tx,trading,redis,new ObjectMapper(),5000,15000,false);
        var second=new ShopCache(db,tx,trading,redis,new ObjectMapper(),5000,15000,false);
        var notifications=new CacheNotifications();var executor=notifications.cacheNotificationExecutor();executor.initialize();
        var listener=notifications.cacheNotifications(lettuce,second,executor);listener.afterPropertiesSet();listener.start();
        long id=next();
        try {
            first.save(id,"before","test");assertEquals("before",second.get(id).name());
            first.save(id,"after","test");
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500);
            while(!"after".equals(second.get(id).name()) && System.nanoTime()<deadline) Thread.sleep(10);
            assertEquals("after",second.get(id).name());
            listener.stop();
            // Unsubscribe callbacks may still clear second's L1. Use a genuinely
            // unsubscribed instance to isolate missed-notice TTL from that race.
            var disconnected=new ShopCache(db,tx,trading,redis,new ObjectMapper(),5000,15000,false);
            try {
                assertEquals("after",disconnected.get(id).name());
                first.save(id,"missed","test");assertEquals("after",disconnected.get(id).name());Thread.sleep(1100);
                assertEquals("missed",disconnected.get(id).name());
            } finally {disconnected.close();}
        } finally { listener.destroy();executor.shutdown();first.close();second.close(); }
    }
    @Test void l1CannotExtendRemainingRedisHardDeadline() throws Exception {
        var local=new ShopCache(db,tx,trading,redis,new ObjectMapper(),5000,15000,false);
        long id=next();String key=ShopCache.key(id);
        try {
            cache.save(id,"database","new");
            long deadline=System.currentTimeMillis()+150;
            redis.opsForValue().set(key,new ObjectMapper().writeValueAsString(
                new ShopCache.Entry(new ShopCache.Shop(id,"old","",1),deadline,deadline)),Duration.ofMillis(150));
            assertEquals("old",local.get(id).name());Thread.sleep(220);
            assertEquals("database",local.get(id).name());
        } finally { local.close(); }
    }
}
