package com.hmdp.upgrade;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Immutable local snapshots; a shared insertion journal closes the cross-instance update gap. */
@Component
public class ShopBloom {
    static final String EPOCH="ux:shop:bloom:{index}:epoch", ADDED="ux:shop:bloom:{index}:added";
    static final DefaultRedisScript<Long> CHECK=new DefaultRedisScript<>("""
        if redis.call('GET',KEYS[1])~=ARGV[1] or redis.call('EXISTS',KEYS[2])==0 then return -1 end
        return redis.call('SISMEMBER',KEYS[2],ARGV[2])
        """,Long.class);
    static final DefaultRedisScript<String> REGISTER=new DefaultRedisScript<>("""
        if not redis.call('GET',KEYS[1]) or redis.call('EXISTS',KEYS[2])==0 then redis.call('SET',KEYS[1],ARGV[1]) end
        redis.call('SADD',KEYS[2],ARGV[2])
        return redis.call('GET',KEYS[1])
        """,String.class);
    static final class Filter {
        final int size,hashes;final BitSet bits;
        Filter(int expected,double probability) {
            size=(int)Math.ceil(-expected*Math.log(probability)/(Math.log(2)*Math.log(2)));
            hashes=Math.max(1,(int)Math.round((double)size/expected*Math.log(2)));bits=new BitSet(size);
        }
        private static long mix(long x) { x=(x^(x>>>30))*0xbf58476d1ce4e5b9L;x=(x^(x>>>27))*0x94d049bb133111ebL;return x^(x>>>31); }
        private int index(long id,int n) { return (int)Math.floorMod(mix(id)+n*(mix(id^0x9e3779b97f4a7c15L)|1),size); }
        void add(long id) { for(int i=0;i<hashes;i++) bits.set(index(id,i)); }
        boolean contains(long id) { for(int i=0;i<hashes;i++) if(!bits.get(index(id,i))) return false;return true; }
    }
    record Snapshot(Filter filter,String epoch,long count) {}
    private final JdbcTemplate db;private final StringRedisTemplate redis;private final Transactions tx;
    private volatile Snapshot current;
    private final LongAdder checks=new LongAdder(),blocked=new LongAdder(),bypassed=new LongAdder();
    public ShopBloom(JdbcTemplate db,StringRedisTemplate redis,Transactions tx) { this.db=db;this.redis=redis;this.tx=tx; }
    @EventListener(ApplicationReadyEvent.class) public void start() { rebuild(); }
    @Scheduled(cron="0 0 3 * * *",zone="Asia/Shanghai")
    public synchronized void rebuild() {
        try {
            if(tx==null) rebuildLocked();
            else tx.run(()->{lockIndex();rebuildLocked();return null;});
        } catch(RuntimeException unavailable) {current=null;bypassed.increment();}
    }
    private void lockIndex() {
        db.queryForObject("SELECT id FROM ux_shop_index_guard WHERE id=1 FOR UPDATE",Integer.class);
    }
    private void rebuildLocked() {
        try {
            redis.execute(REGISTER,List.of(EPOCH,ADDED),Trading.uuid(),"0");
            String epoch=redis.opsForValue().get(EPOCH);if(epoch==null) {current=null;return;}
            Filter next=new Filter(1_000_000,0.01);long cursor=0,count=0;
            for(;;) {
                List<Long> ids=db.queryForList("SELECT id FROM ux_shop WHERE id>? ORDER BY id LIMIT 1000",Long.class,cursor);
                if(ids.isEmpty()) break;
                for(long id:ids) next.add(id);
                count+=ids.size();cursor=ids.get(ids.size()-1);
                if(count>1_000_000) {current=null;return;}
            }
            // The shared journal is not cleared: other instances may still hold older snapshots.
            if(epoch.equals(redis.opsForValue().get(EPOCH))) current=new Snapshot(next,epoch,count);
            else current=null;
        } catch(RuntimeException unavailable) { current=null;bypassed.increment(); }
    }
    public void beforeInsert(long id) {
        if(tx!=null) {
            if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Register inside the shop insert transaction");
            lockIndex();
        }
        try { if(redis.execute(REGISTER,List.of(EPOCH,ADDED),Trading.uuid(),Long.toString(id))==null) throw new IllegalStateException("Missing journal acknowledgement"); }
        catch(RuntimeException unavailable) {throw new Problem(503,"SHOP_INDEX_UNAVAILABLE");}
    }
    public boolean mightContain(long id) {
        checks.increment();Snapshot snapshot=current;
        if(snapshot==null) {bypassed.increment();return true;}
        if(snapshot.filter().contains(id)) return true;
        try {
            Long recent=redis.execute(CHECK,List.of(EPOCH,ADDED),snapshot.epoch(),Long.toString(id));
            if(Long.valueOf(0).equals(recent)) {blocked.increment();return false;}
            if(!Long.valueOf(1).equals(recent)) {current=null;bypassed.increment();}
            return true;
        } catch(RuntimeException unavailable) {bypassed.increment();return true;}
    }
    public Map<String,Long> metrics() {var s=current;return Map.of("checks",checks.sum(),"blocked",blocked.sum(),"bypassed",bypassed.sum(),"ready",s==null?0L:1L,"entries",s==null?0L:s.count());}
}
