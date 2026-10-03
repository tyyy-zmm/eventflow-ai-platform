package com.hmdp.upgrade;

import java.sql.Timestamp;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Redis is a disposable due-time index; MySQL is the durable task authority. */
@Component
public class DelayedClose {
    private final JdbcTemplate db;private final Trading trading;private final StringRedisTemplate redis;
    @Value("${upgrade.jobs:true}") private boolean jobs;
    @Value("${upgrade.close-queue-key:ux:close:due}") private String key="ux:close:due";
    final LongAdder failures=new LongAdder();
    public DelayedClose(JdbcTemplate db,Trading trading,StringRedisTemplate redis) {this.db=db;this.trading=trading;this.redis=redis;}
    public void enqueue() {
        var rows=db.queryForList("SELECT request_id,due_at FROM ux_close_task WHERE done_at IS NULL AND next_enqueue_at<=CURRENT_TIMESTAMP(3) ORDER BY next_enqueue_at LIMIT 100");
        for(var row:rows) {
            redis.opsForZSet().add(key,(String)row.get("request_id"),((Timestamp)row.get("due_at")).getTime());
            db.update("UPDATE ux_close_task SET next_enqueue_at=? WHERE request_id=? AND done_at IS NULL",Timestamp.from(trading.now().plusSeconds(30)),row.get("request_id"));
        }
    }
    public void drain() {
        var ids=redis.opsForZSet().rangeByScore(key,0,trading.now().toEpochMilli(),0,100);
        if(ids==null) return;
        for(String id:ids) if(close(id)) redis.opsForZSet().remove(key,id);
    }
    boolean close(String id) {
        var rows=db.queryForList("SELECT user_id,done_at FROM ux_close_task WHERE request_id=?",id);
        if(rows.isEmpty() || rows.get(0).get("done_at")!=null) return true;
        var order=trading.transition(((Number)rows.get(0).get("user_id")).longValue(),id,"expire");
        if("PENDING_CONFIRM".equals(order.get("state"))) return false;
        db.update("UPDATE ux_close_task SET done_at=CURRENT_TIMESTAMP(3) WHERE request_id=? AND done_at IS NULL",id);return true;
    }
    public void reconcile() {
        for(String id:db.queryForList("SELECT request_id FROM ux_close_task WHERE done_at IS NULL AND due_at<=CURRENT_TIMESTAMP(3) ORDER BY due_at LIMIT 100",String.class)) close(id);
    }
    @Scheduled(fixedDelay=1000) public void tick() {
        if(!jobs) return;
        try {enqueue();drain();} catch(RuntimeException e) {failures.increment();}
    }
    @Scheduled(fixedDelay=10000) public void fallback() {
        if(jobs) try {reconcile();} catch(RuntimeException e) {failures.increment();}
    }
}
