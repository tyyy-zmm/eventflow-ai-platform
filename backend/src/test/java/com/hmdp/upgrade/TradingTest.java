package com.hmdp.upgrade;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TradingTest {
    JdbcTemplate db;
    Trading trading;
    Transactions tx;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__upgrade.sql"),new ClassPathResource("db/migration/V3__reservation_actions.sql")).execute(source);
        db=new JdbcTemplate(source);tx=new Transactions(new DataSourceTransactionManager(source));trading=new Trading(db,tx);
        seed(1,100);
    }
    void seed(long id,int capacity) {
        Instant now=Instant.now();
        db.update("INSERT INTO ux_activity VALUES(?,?,?,?,?,?,?)",id,capacity,capacity,990,
            Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(600)),Timestamp.from(now.plusSeconds(900)));
    }
    Trading.Event event(Trading.Request r) {
        return db.queryForObject("SELECT * FROM ux_outbox WHERE request_id=?",(s,n)->new Trading.Event(1,s.getString("id"),r.id(),r.activityId()),r.id());
    }
    void valid() {
        assertEquals(0L,trading.invariants().get("stockViolations"));
        assertEquals(0L,trading.invariants().get("stateViolations"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void compactAndLegacyReturnCommittedStateAndPreserveTerminalReplay(boolean compact) {
        org.springframework.test.util.ReflectionTestUtils.setField(trading,"compactTransactions",compact);
        var accepted=trading.accept(10,"compact001",1,false);
        assertEquals(trading.result(10,accepted.id()),accepted);
        var completed=trading.process(event(accepted));
        assertEquals(trading.result(10,accepted.id()),completed);
        assertEquals(completed,trading.process(event(accepted)));
        trading.transition(10,accepted.id(),"cancel");
        assertEquals(completed,trading.process(event(accepted)));
        assertEquals("CANCELLED",trading.order(10,accepted.id()).get("state"));valid();
    }
    @Test void orphanAdjudicationFencesLateAccept() {
        var terminal=trading.adjudicateReservation(1,"orphan0001",1);
        assertEquals("EXPIRED",terminal.state());
        assertEquals(terminal.id(),trading.accept(1,"orphan0001",1,true).id());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_order",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE action='RELEASE'",Integer.class));
        valid();
    }
    @Test void adjudicationDoesNotReleaseCommittedAcceptance() {
        var accepted=trading.accept(1,"accepted01",1,false);
        assertEquals(accepted.id(),trading.adjudicateReservation(1,"accepted01",1).id());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action",Integer.class));
        assertEquals("SUCCEEDED",trading.process(event(accepted)).state());valid();
    }
    @Test void adjudicationAndAcceptRaceHasSingleDurableOutcome() throws Exception {
        var executor=Executors.newFixedThreadPool(2);
        try {
            for(int i=0;i<30;i++) {
                String key="race_orphan_"+i;long user=100+i;
                var start=new CountDownLatch(1);
                var accept=executor.submit(()->{ start.await();return trading.accept(user,key,1,true); });
                var expire=executor.submit(()->{ start.await();return trading.adjudicateReservation(user,key,1); });
                start.countDown();
                assertEquals(accept.get().id(),expire.get().id());
                var result=trading.existing(user,key,1);
                int orders=db.queryForObject("SELECT COUNT(*) FROM ux_order WHERE request_id=?",Integer.class,result.id());
                assertEquals("SUCCEEDED".equals(result.state())?1:0,orders);
            }
            valid();
        } finally { executor.shutdownNow(); }
    }
    @Test void acceptedIsNotAnOrder() {
        var r=trading.accept(1,"request001",1,false);
        assertEquals("ACCEPTED",r.state());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_order",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_outbox",Integer.class));valid();
    }
    @Test void replaySameKeyAndPayload() {
        var r=trading.accept(1,"request001",1,false);
        assertEquals(r.id(),trading.accept(1,"request001",1,false).id());
        seed(2,1);assertEquals(409,assertThrows(Problem.class,()->trading.accept(1,"request001",2,false)).status);
        valid();
    }
    @Test void duplicateDeliveryHasOneEffect() {
        var r=trading.accept(1,"request001",1,false);var e=event(r);
        for(int i=0;i<10;i++) trading.process(e);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_order",Integer.class));valid();
    }
    @Test void duplicateUserNewRequestRejectedIncludingAfterCancellation() {
        var r=trading.accept(1,"request001",1,true);
        trading.transition(1,r.id(),"cancel");
        var second=trading.accept(1,"request002",1,true);
        assertEquals("ALREADY_PURCHASED",second.reason());valid();
    }
    @Test void cancellationIsIdempotent() {
        var r=trading.accept(1,"request001",1,true);
        for(int i=0;i<10;i++) trading.transition(1,r.id(),"cancel");
        assertEquals(100,db.queryForObject("SELECT available FROM ux_activity WHERE id=1",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_invalidation",Integer.class));valid();
    }
    @Test void lateMessageCannotResurrectExpiredRequest() {
        var r=trading.accept(1,"request001",1,false);
        db.update("UPDATE ux_request SET deadline=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),r.id());
        trading.expireRequests();trading.process(event(r));
        assertEquals("EXPIRED",trading.result(1,r.id()).state());valid();
    }
    @Test void confirmationAfterDeadlineExpiresAndReleases() {
        var r=trading.accept(1,"request001",1,true);
        db.update("UPDATE ux_order SET confirm_until=?",Timestamp.from(Instant.now().minusSeconds(1)));
        assertEquals("EXPIRED",trading.transition(1,r.id(),"confirm").get("state"));valid();
    }
    @Test void resultAndOrderCheckOwnership() {
        var r=trading.accept(1,"request001",1,true);
        assertEquals(404,assertThrows(Problem.class,()->trading.result(2,r.id())).status);
        assertEquals(404,assertThrows(Problem.class,()->trading.transition(2,r.id(),"cancel")).status);
    }
    @Test void priceIsTakenFromServer() {
        var r=trading.accept(1,"request001",1,true);
        assertEquals(990,((Number)trading.order(1,r.id()).get("price_cents")).intValue());
    }
    @Test void entireTransactionRollsBackOnFailure() {
        assertThrows(IllegalStateException.class,()->tx.run(()->{
            db.update("UPDATE ux_activity SET available=available-1 WHERE id=1");
            throw new IllegalStateException("injected after stock decrement");
        }));valid();
    }
    @Test void actualOrderInsertFailureRollsBackDebitAndAcceptance() {
        JdbcTemplate failing=new JdbcTemplate(db.getDataSource()) {
            @Override public int update(String sql,Object... args) {
                if(sql.startsWith("INSERT INTO ux_order")) throw new org.springframework.dao.DataIntegrityViolationException("injected insert failure");
                return super.update(sql,args);
            }
        };
        var service=new Trading(failing,tx);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.accept(1,"rollback001",1,true));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_request",Integer.class));valid();
    }
    @Test void invalidEventCannotTouchOtherActivity() {
        var r=trading.accept(1,"request001",1,false);seed(2,5);
        var e=event(r);
        assertThrows(Problem.class,()->trading.process(new Trading.Event(1,e.eventId(),r.id(),2)));valid();
    }
    @Test void thousandUsersCompeteForHundredSlots() throws Exception {
        var executor=Executors.newFixedThreadPool(16);
        try {
            var tasks=new ArrayList<Callable<String>>();
            for(int i=1;i<=1000;i++) { long user=i;tasks.add(()->trading.accept(user,"request000"+user,1,true).state()); }
            var results=executor.invokeAll(tasks);int success=0;
            for(var result:results) if(result.get().equals("SUCCEEDED")) success++;
            assertEquals(100,success);valid();
        } finally { executor.shutdownNow(); }
    }
    @Test void confirmCancelRacePreservesStock() throws Exception {
        var r=trading.accept(1,"request001",1,true);
        var executor=Executors.newFixedThreadPool(2);
        try {
            for(var f:executor.invokeAll(List.<Callable<Object>>of(()->trading.transition(1,r.id(),"confirm"),()->trading.transition(1,r.id(),"cancel")))) f.get();
            valid();
        } finally { executor.shutdownNow(); }
    }
    @Test void databaseUniqueAndStockConstraintsAreFinalFence() {
        assertThrows(Exception.class,()->db.update("UPDATE ux_activity SET available=-1 WHERE id=1"));
        var r=trading.accept(1,"request001",1,true);
        assertThrows(Exception.class,()->db.update("INSERT INTO ux_order SELECT 'other',request_id,activity_id,user_id,price_cents,state,confirm_until,created_at FROM ux_order WHERE request_id=?",r.id()));valid();
    }
}
