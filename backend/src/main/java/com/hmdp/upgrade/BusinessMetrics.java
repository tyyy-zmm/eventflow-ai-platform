package com.hmdp.upgrade;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BusinessMetrics implements MeterBinder {
    private final JdbcTemplate db;
    private volatile long outboxPending,outboxOldestSeconds,poisonTotal,invalidationPending,closeOverdue,refundPending;
    BusinessMetrics(JdbcTemplate db) {this.db=db;}
    @Scheduled(fixedDelay=5000) public void refresh() {
        try {
            var outbox=db.queryForMap("SELECT COUNT(*) AS pending,COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP(3)),0) AS age FROM ux_outbox WHERE sent_at IS NULL");
            outboxPending=((Number)outbox.get("pending")).longValue();outboxOldestSeconds=((Number)outbox.get("age")).longValue();
            poisonTotal=db.queryForObject("SELECT COUNT(*) FROM ux_poison",Long.class);
            invalidationPending=db.queryForObject("SELECT COUNT(*) FROM ux_invalidation",Long.class);
            closeOverdue=db.queryForObject("SELECT COUNT(*) FROM ux_close_task WHERE done_at IS NULL AND due_at<=CURRENT_TIMESTAMP(3)",Long.class);
            refundPending=db.queryForObject("SELECT COUNT(*) FROM ux_refund WHERE done_at IS NULL",Long.class);
        } catch(org.springframework.dao.DataAccessException ignored) { }
    }
    @Override public void bindTo(MeterRegistry registry) {
        Gauge.builder("life.business.outbox.pending",this,x->x.outboxPending).register(registry);
        Gauge.builder("life.business.outbox.oldest.age.seconds",this,x->x.outboxOldestSeconds).register(registry);
        Gauge.builder("life.business.poison.total",this,x->x.poisonTotal).register(registry);
        Gauge.builder("life.business.invalidation.pending",this,x->x.invalidationPending).register(registry);
        Gauge.builder("life.business.close.overdue",this,x->x.closeOverdue).register(registry);
        Gauge.builder("life.business.refund.pending",this,x->x.refundPending).register(registry);
    }
}
