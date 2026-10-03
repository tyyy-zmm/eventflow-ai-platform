package com.hmdp.upgrade;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

class PaymentsTest {
    JdbcTemplate db;Transactions tx;Trading trading;Payments payments;DelayedClose close;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__upgrade.sql"),new ClassPathResource("db/migration/V3__reservation_actions.sql"),new ClassPathResource("db/migration/V11__payment_and_close_tasks.sql")).execute(source);
        db=new JdbcTemplate(source);tx=new Transactions(new DataSourceTransactionManager(source));trading=new Trading(db,tx);
        ReflectionTestUtils.setField(trading,"closeTasks",new CloseTasks(db));payments=new Payments(db,tx,trading);
        close=new DelayedClose(db,trading,mock(StringRedisTemplate.class));
        var now=Instant.now();db.update("INSERT INTO ux_activity VALUES(1,100,100,990,?,?,?)",Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(600)),Timestamp.from(now.plusSeconds(900)));
    }
    String order(long user) {return trading.accept(user,"pay_request_"+user,1,true).id();}
    String payment(long user,String request) {return (String)payments.create(user,request).get("id");}
    int count(String table) {return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    void due(String request) {
        var past=Timestamp.from(Instant.now().minusSeconds(1));
        db.update("UPDATE ux_order SET confirm_until=? WHERE request_id=?",past,request);
        db.update("UPDATE ux_close_task SET due_at=? WHERE request_id=?",past,request);
    }
    void valid() {assertEquals(0L,trading.invariants().get("stockViolations"));}
    @Test void duplicateReceiptCreatesOnePaymentEffectAndCannotBeCancelled() {
        String r=order(1),p=payment(1,r);
        for(int i=0;i<5;i++) assertEquals("APPLIED",payments.received(1,p,"channel_001",990).get("outcome"));
        close.reconcile();trading.transition(1,r,"cancel");
        assertEquals("CONFIRMED",trading.order(1,r).get("state"));assertEquals(1,count("ux_payment_receipt"));assertEquals(0,count("ux_refund"));valid();
    }
    @Test void amountAndOwnershipCheckedAndLegacyConfirmCannotBypassPayment() {
        String r=order(1),p=payment(1,r);
        assertThrows(Problem.class,()->payments.received(2,p,"channel_002",990));
        assertThrows(Problem.class,()->payments.received(1,p,"channel_002",1));
        assertThrows(Problem.class,()->trading.transition(1,r,"confirm"));
        assertEquals(0,count("ux_payment_receipt"));assertEquals("PENDING_CONFIRM",trading.order(1,r).get("state"));valid();
    }
    @Test void closeThenPaymentRefundsAndProviderReplayAfterCrashIsIdempotent() {
        String r=order(1),p=payment(1,r);due(r);close.reconcile();close.reconcile();
        assertEquals("REFUND_PENDING",payments.received(1,p,"channel_003",990).get("outcome"));
        payments.providerRefund("channel_003",990); // Provider committed; local acknowledgement lost.
        new Payments(db,tx,trading).refunds();payments.refunds();
        assertEquals(1,count("ux_sandbox_refund"));assertEquals("REFUNDED",payments.received(1,p,"channel_003",990).get("outcome"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE action='RESTORE'",Integer.class));valid();
    }
    @Test void secondDistinctChargeRefundsWithoutUndoingPaidOrder() {
        String r=order(1),p=payment(1,r);payments.received(1,p,"channel_004",990);
        payments.received(1,p,"channel_005",990);payments.refunds();
        assertEquals("PAID",payments.get(1,p).get("state"));assertEquals("CONFIRMED",trading.order(1,r).get("state"));assertEquals(1,count("ux_refund"));valid();
    }
    @Test void failedRefundIsDurableAndRetrySucceeds() {
        String r=order(1),p=payment(1,r);trading.transition(1,r,"cancel");payments.received(1,p,"channel_006",990);
        Payments unavailable=new Payments(db,tx,trading) {@Override void providerRefund(String c,int a) {throw new IllegalStateException("provider unavailable");}};
        unavailable.refunds();assertEquals(1,db.queryForObject("SELECT attempts FROM ux_refund",Integer.class));assertEquals(0,count("ux_sandbox_refund"));
        db.update("UPDATE ux_refund SET next_at=CURRENT_TIMESTAMP(3)");payments.refunds();assertEquals(1,count("ux_sandbox_refund"));valid();
    }
    @Test void multipleLateChargesStayPendingUntilEveryRefundCompletes() {
        String r=order(1),p=payment(1,r);trading.transition(1,r,"cancel");
        payments.received(1,p,"late_charge_1",990);payments.received(1,p,"late_charge_2",990);
        db.update("UPDATE ux_refund SET next_at=? WHERE channel_id='late_charge_2'",Timestamp.from(Instant.now().plusSeconds(60)));
        payments.refunds();assertEquals("REFUND_PENDING",payments.get(1,p).get("state"));
        db.update("UPDATE ux_refund SET next_at=CURRENT_TIMESTAMP(3)");payments.refunds();assertEquals("REFUNDED",payments.get(1,p).get("state"));
        payments.received(1,p,"late_charge_3",990);assertEquals("REFUND_PENDING",payments.get(1,p).get("state"));
        payments.refunds();assertEquals("REFUNDED",payments.get(1,p).get("state"));assertEquals(3,count("ux_sandbox_refund"));valid();
    }
    @Test void rollbackRemovesOrderAndCloseTaskTogether() {
        assertThrows(IllegalStateException.class,()->tx.run(()->{order(1);throw new IllegalStateException("crash");}));
        assertEquals(0,count("ux_close_task"));assertEquals(0,count("ux_order"));valid();
    }
    @Test void expiredPaymentAndCloseRaceRefundOnceAndRestoreOnce() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {for(int i=1;i<=20;i++) {
            long user=i;String r=order(user),p=payment(user,r);due(r);var go=new CountDownLatch(1);
            var a=pool.submit(()->{go.await();return payments.received(user,p,"race_channel_"+user,990);});
            var b=pool.submit(()->{go.await();return close.close(r);});go.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals("EXPIRED",trading.order(user,r).get("state"));
        }} finally {pool.shutdownNow();}
        payments.refunds();assertEquals(20,count("ux_sandbox_refund"));assertEquals(20,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE action='RESTORE'",Integer.class));valid();
    }
    @Test void redisUnavailableAndRestartStillCloseFromDurableTask() {
        String r=order(1);due(r);
        new DelayedClose(db,trading,mock(StringRedisTemplate.class)).reconcile();
        assertEquals("EXPIRED",trading.order(1,r).get("state"));valid();
    }
    @Test void paymentAndCancellationRaceHaveOneTerminalOutcome() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {for(int i=1;i<=20;i++) {
            long user=i;String r=order(user),p=payment(user,r);var go=new CountDownLatch(1);
            var a=pool.submit(()->{go.await();return payments.received(user,p,"cancel_race_"+user,990);});
            var b=pool.submit(()->{go.await();return trading.transition(user,r,"cancel");});go.countDown();
            var receipt=a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            String state=(String)trading.order(user,r).get("state");
            assertEquals("CONFIRMED".equals(state)?"APPLIED":"REFUND_PENDING",receipt.get("outcome"));
            assertTrue(java.util.Set.of("CONFIRMED","CANCELLED").contains(state));valid();
        }} finally {pool.shutdownNow();}
    }
    @Test void receiptAndCloseRollbackDoNotLeavePartialMoneyOrStockState() {
        String r=order(1),p=payment(1,r);
        assertThrows(IllegalStateException.class,()->tx.run(()->{payments.received(1,p,"rollback_receipt",990);throw new IllegalStateException("crash");}));
        assertEquals(0,count("ux_payment_receipt"));assertEquals("OPEN",payments.get(1,p).get("state"));
        assertEquals("PENDING_CONFIRM",trading.order(1,r).get("state"));
        due(r);
        assertThrows(IllegalStateException.class,()->tx.run(()->{close.close(r);throw new IllegalStateException("crash");}));
        assertEquals("PENDING_CONFIRM",trading.order(1,r).get("state"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE action='RESTORE'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_close_task WHERE done_at IS NOT NULL",Integer.class));valid();
    }
}
