package com.hmdp.upgrade;

import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

class OrderProjectionTest {
    JdbcTemplate db;Transactions tx;Trading trading;OrderProjection projection;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__upgrade.sql"),new ClassPathResource("db/migration/V2__customer_sessions_and_storefront.sql"),new ClassPathResource("db/migration/V3__reservation_actions.sql"),new ClassPathResource("db/migration/V8__order_read_projection.sql"),new ClassPathResource("db/migration/V10__projection_display_fields.sql")).execute(source);
        db=new JdbcTemplate(source);tx=new Transactions(new DataSourceTransactionManager(source));trading=new Trading(db,tx);projection=new OrderProjection(db);
        ReflectionTestUtils.setField(trading,"projection",projection);
        var now=Instant.now();db.update("INSERT INTO ux_activity VALUES(1,100,100,990,?,?,?)",Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(600)),Timestamp.from(now.plusSeconds(900)));
    }
    @Test void sourceCommitThenRetryAndCancellationConvergeWithoutCrossUserLeak() {
        var r=trading.accept(33,"projection01",1,true);
        assertTrue(projection.list(33,1).isEmpty());assertEquals(1,projection.drain());assertEquals(0,projection.drain());
        assertEquals("PENDING_CONFIRM",projection.list(33,1).get(0).state());assertTrue(projection.list(1,1).isEmpty());
        trading.transition(33,r.id(),"cancel");projection.drain();
        assertEquals("CANCELLED",projection.list(33,1).get(0).state());
        assertEquals(0L,trading.invariants().get("stockViolations"));
    }
    @Test void replayAfterTargetWriteBeforeAcknowledgementAndOldMessagesCannotRegressState() {
        var request=trading.accept(8,"projection02",1,true);
        var old=new HashMap<>(db.queryForMap("SELECT o.*,p.revision FROM ux_order o JOIN ux_order_projection p ON o.id=p.order_id"));
        projection.apply(old); // Simulate crash before source acknowledgement.
        assertEquals(1,projection.drain());
        trading.transition(8,request.id(),"confirm");projection.drain();projection.apply(old);
        assertEquals("CONFIRMED",projection.list(8,1).get(0).state());
        db.update("DELETE FROM "+OrderProjection.table(8));projection.replay("");projection.drain();
        assertEquals("CONFIRMED",projection.list(8,1).get(0).state());
    }
    @Test void rollbackAlsoRemovesProjectionIntent() {
        assertThrows(IllegalStateException.class,()->tx.run(()->{trading.accept(2,"projection03",1,true);throw new IllegalStateException("abort");}));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_order_projection",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_order",Integer.class));
        assertThrows(IllegalStateException.class,()->projection.changed("outside"));
    }
    @Test void routesAllPartitionsDeterministically() {
        var seen=new java.util.HashSet<Integer>();for(long i=1;i<=32;i++)seen.add(OrderProjection.shard(i));assertEquals(32,seen.size());
        assertEquals(OrderProjection.table(1),OrderProjection.table(33));
    }
    @Test void reconciliationFindsMissingAndDivergentRowsAndRepairsThem() {
        var request=trading.accept(17,"projection04",1,true);projection.drain();
        db.update("UPDATE "+OrderProjection.table(17)+" SET state='CANCELLED' WHERE request_id=?",request.id());
        assertEquals(1,projection.reconcile());assertEquals(1,projection.drain());
        assertEquals("PENDING_CONFIRM",projection.list(17,1).get(0).state());
        db.update("DELETE FROM "+OrderProjection.table(17)+" WHERE request_id=?",request.id());
        while(projection.reconcile()==0) {}
        projection.drain();assertEquals(1,projection.count(17));
        assertEquals(2L,projection.status().get("repaired"));
    }
}
