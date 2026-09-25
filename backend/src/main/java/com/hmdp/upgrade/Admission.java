package com.hmdp.upgrade;

import java.util.List;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Admission {
    static final DefaultRedisScript<Long> WINDOW=new DefaultRedisScript<>("""
        local u=tonumber(redis.call('GET',KEYS[1]) or '0')
        local a=tonumber(redis.call('GET',KEYS[2]) or '0')
        if u>=tonumber(ARGV[1]) or a>=tonumber(ARGV[2]) then return 0 end
        if redis.call('INCR',KEYS[1])==1 then redis.call('PEXPIRE',KEYS[1],1000) end
        if redis.call('INCR',KEYS[2])==1 then redis.call('PEXPIRE',KEYS[2],1000) end
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final JdbcTemplate db;
    private final int userRate,activityRate,limit,oldest;
    private final Semaphore slots=new Semaphore(16);
    public Admission(StringRedisTemplate redis,JdbcTemplate db,
        @Value("${upgrade.rate-user:5}") int userRate,@Value("${upgrade.rate-activity:200}") int activityRate,
        @Value("${upgrade.backlog-limit:5000}") int limit,@Value("${upgrade.oldest-seconds:60}") int oldest) {
        this.redis=redis;this.db=db;this.userRate=userRate;this.activityRate=activityRate;this.limit=limit;this.oldest=oldest;
    }
    public void enter(long user,long activity) {
        if(!slots.tryAcquire()) throw new Problem(503,"ADMISSION_BUSY");
        try {
            String base="ux:rate:{"+activity+"}";
            Long ok=redis.execute(WINDOW,List.of(base+":user:"+user,base+":activity"),""+userRate,""+activityRate);
            if(!Long.valueOf(1).equals(ok)) throw new Problem(429,"RATE_LIMITED");
            if(Boolean.TRUE.equals(redis.hasKey("ux:sold:{"+activity+"}"))) throw new Problem(409,"SOLD_OUT_HINT");
            var backlog=db.queryForMap("SELECT COUNT(*) AS total, MIN(created_at) AS oldest FROM ux_request WHERE state='ACCEPTED'");
            if(((Number)backlog.get("total")).longValue()>=limit) throw new Problem(503,"BACKLOG_FULL");
            if(backlog.get("oldest")!=null) {
                var time=((java.sql.Timestamp)backlog.get("oldest")).toInstant();
                var now=db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
                if(Duration.between(time,now).getSeconds()>=oldest) throw new Problem(503,"BACKLOG_TOO_OLD");
            }
        } catch(Exception e) {
            slots.release();
            if(e instanceof Problem p) throw p;
            throw new Problem(503,"ADMISSION_UNAVAILABLE");
        }
    }
    public void leave() { slots.release(); }
    public void soldOut(long activity) {
        try { redis.opsForValue().set("ux:sold:{"+activity+"}","1",Duration.ofSeconds(2)); }
        catch(Exception ignored) { /* A hint failure must not roll back a committed order. */ }
    }
}
