package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.dao.DataAccessException;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ShopCache {
    public record Shop(long id,String name,String description,long revision) {}
    public record Entry(Shop shop,long softUntil,long hardUntil) {
        public Entry(Shop shop,long softUntil) { this(shop,softUntil,0); }
    }
    record LocalEntry(Entry value,long until) {}
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ShopBloom bloom;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private CacheCircuit circuit;
    private final Cache<String,LocalEntry> local=Caffeine.newBuilder().maximumSize(10_000)
        .expireAfterWrite(Duration.ofSeconds(2)).build();
    private static final class Guard { long generation; }
    private final Guard[] guards=new Guard[256];
    private final LongAdder localHits=new LongAdder(),fallbacks=new LongAdder(),fallbackRejected=new LongAdder();
    private long fallbackSecond;private int fallbackCount;
    static final String INVALIDATION_CHANNEL="ux:shop:invalidate";
    static final DefaultRedisScript<Long> FILL=new DefaultRedisScript<>("""
        if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end
        redis.call('SET',KEYS[2],ARGV[2],'PX',ARGV[3])
        redis.call('DEL',KEYS[1])
        return 1
        """,Long.class);
    static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("""
        if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end
        return 0
        """,Long.class);
    static final DefaultRedisScript<Long> INVALIDATE=new DefaultRedisScript<>("""
        local removed=redis.call('DEL',KEYS[1],KEYS[2])
        if ARGV[1] then redis.call('PUBLISH',ARGV[1],KEYS[1]) end
        return removed
        """,Long.class);
    private final JdbcTemplate db,reads;
    private final Transactions tx;
    private final Trading trading;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final long soft,hard;
    private final boolean jobs;
    private final Semaphore readSlots=new Semaphore(4);
    private final ThreadPoolExecutor rebuild=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
        new ThreadPoolExecutor.AbortPolicy());
    private final LongAdder ttlHits=new LongAdder(),databaseReads=new LongAdder(),hits=new LongAdder(),staleHits=new LongAdder(),rejected=new LongAdder();
    public ShopCache(JdbcTemplate db,Transactions tx,Trading trading,StringRedisTemplate redis,ObjectMapper json,
        @Value("${upgrade.cache-soft-ms:5000}") long soft,@Value("${upgrade.cache-hard-ms:15000}") long hard,
        @Value("${upgrade.jobs:true}") boolean jobs) {
        if(soft<=0 || hard<=soft) throw new IllegalArgumentException("cache hard expiry must exceed soft expiry");
        for(int i=0;i<guards.length;i++) guards[i]=new Guard();
        this.db=db;this.reads=new JdbcTemplate(db.getDataSource());reads.setQueryTimeout(2);
        this.tx=tx;this.trading=trading;this.redis=redis;this.json=json;this.soft=soft;this.hard=hard;this.jobs=jobs;
    }
    static String key(long id) { return "ux:shop:{"+id+"}"; }
    public Map<String,Long> metrics() { return Map.of("databaseReads",databaseReads.sum(),"hits",hits.sum(),"staleHits",staleHits.sum(),"rebuildRejected",rejected.sum(),"localHits",localHits.sum(),"localSize",local.estimatedSize(),"fallbacks",fallbacks.sum(),"fallbackRejected",fallbackRejected.sum(),"ttlHits",ttlHits.sum()); }
    public Shop simpleTtl(long id) {
        String key="ux:baseline:shop:{"+id+"}";
        try {
            String cached=redis.opsForValue().get(key);
            if(cached!=null) { ttlHits.increment();return json.readValue(cached,Shop.class); }
            Shop shop=direct(id);
            redis.opsForValue().set(key,json.writeValueAsString(shop),Duration.ofMillis(hard));
            return shop;
        } catch(Problem p) { throw p; }
        catch(Exception e) { throw new Problem(503,"SHOP_UNAVAILABLE"); }
    }
    public Shop direct(long id) {
        if(id<=0) throw new Problem(400,"INVALID_SHOP");
        if(!readSlots.tryAcquire()) throw new Problem(503,"SHOP_DB_BUSY");
        try {
            databaseReads.increment();
            var rows=reads.query("SELECT * FROM ux_shop WHERE id=?",(r,n)->new Shop(r.getLong("id"),r.getString("name"),r.getString("description"),r.getLong("revision")),id);
            return rows.isEmpty()?null:rows.get(0);
        } catch(DataAccessException unavailable) { throw new Problem(503,"SHOP_DB_UNAVAILABLE"); }
        finally { readSlots.release(); }
    }
    private Entry read(String key) throws Exception {
        String value;long began=System.nanoTime();
        try { value=redis.opsForValue().get(key);if(circuit!=null)circuit.record(System.nanoTime()-began,true); }
        catch(DataAccessException unavailable) {if(circuit!=null)circuit.record(System.nanoTime()-began,false);throw unavailable;}
        if(value==null) return null;
        Entry entry=json.readValue(value,Entry.class);
        // Legacy payloads remain readable in Redis, but never enter L1 without an absolute deadline.
        if(entry.hardUntil()>0 && entry.hardUntil()<=System.currentTimeMillis()) return null;
        return entry;
    }
    private Guard guard(String key) { return guards[(key.hashCode() & 0x7fffffff)%guards.length]; }
    void invalidateLocal(String key) {
        Guard guard=guard(key);
        synchronized(guard) { guard.generation++;local.invalidate(key); }
    }
    void clearLocal() {
        // Generation guards also reject fills that started before a reconnect.
        for(Guard guard:guards) synchronized(guard) { guard.generation++; }
        local.invalidateAll();
    }
    private synchronized boolean permitFallback() {
        long second=System.nanoTime()/1_000_000_000L;
        if(second!=fallbackSecond) { fallbackSecond=second;fallbackCount=0; }
        return ++fallbackCount<=20;
    }
    public Shop get(long id) {
        if(id<=0) throw new Problem(400,"INVALID_SHOP");
        String key=key(id);Guard guard=guard(key);long generation;
        synchronized(guard) {
            var cached=local.getIfPresent(key);
            if(cached!=null && (cached.until()>System.currentTimeMillis() ||
                (circuit!=null && circuit.degraded() && cached.until()+1000>System.currentTimeMillis() && cached.value().hardUntil()>System.currentTimeMillis()))) {
                localHits.increment();return cached.value().shop();
            }
            local.invalidate(key);generation=guard.generation;
        }
        if(circuit!=null && !circuit.permit()) return fallback(id);
        if(bloom!=null && !bloom.mightContain(id)) return null;
        Entry entry;
        try { entry=load(id); }
        catch(DataAccessException unavailable) {
            return fallback(id);
        } catch(Problem p) { throw p; }
        catch(Exception unavailable) { throw new Problem(503,"SHOP_CACHE_UNAVAILABLE"); }
        long now=System.currentTimeMillis();
        // Never grant a new hard lifetime when copying an entry from Redis.
        long until=Math.min(entry.hardUntil(),now+1000);
        if(entry.softUntil()>now) until=Math.min(until,entry.softUntil());
        else until=Math.min(until,now+100);
        synchronized(guard) {
            if(guard.generation==generation && until>now) local.put(key,new LocalEntry(entry,until));
        }
        return entry.shop();
    }
    private Shop fallback(long id) {
        if(!permitFallback()) {fallbackRejected.increment();throw new Problem(503,"SHOP_FALLBACK_BUSY");}
        fallbacks.increment();return direct(id);
    }
    private Entry load(long id) throws Exception {
        if(id<=0) throw new Problem(400,"INVALID_SHOP");
        String key=key(id),owner=Trading.uuid();
        long deadline=System.nanoTime()+150_000_000L;
        try {
            do {
                Entry entry=read(key);
                if(entry!=null) {
                    hits.increment();
                    if(entry.softUntil()<System.currentTimeMillis()) {
                        staleHits.increment();
                        if(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key+":lock",owner,Duration.ofSeconds(5)))) {
                            try { rebuild.execute(()->{
                                try { refresh(id,key,owner); }
                                catch(Exception ignored) { rejected.increment(); }
                            }); } catch(RejectedExecutionException full) { rejected.increment();release(key,owner); }
                        }
                    }
                    return entry;
                }
                if(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key+":lock",owner,Duration.ofSeconds(5))))
                    return refresh(id,key,owner);
                LockSupport.parkNanos(10_000_000);
                if(Thread.currentThread().isInterrupted()) throw new Problem(503,"SHOP_INTERRUPTED");
            } while(System.nanoTime()<deadline);
            throw new Problem(503,"SHOP_WARMING");
        } catch(Problem p) { throw p; }
        catch(DataAccessException unavailable) { throw unavailable; }
    }
    private Entry refresh(long id,String key,String owner) throws Exception {
        try {
            Entry cached=read(key);
            if(cached!=null && cached.softUntil()>System.currentTimeMillis()) return cached;
            long started=System.currentTimeMillis();
            Shop value=direct(id);
            long ttl=value==null ? 1000 : hard+ThreadLocalRandom.current().nextLong(Math.max(1,hard/10));
            long softDeadline=started+(value==null?1000:soft);
            // TTL is measured from before the read, not from the delayed response.
            long remaining=ttl-(System.currentTimeMillis()-started);
            if(remaining<=0) throw new Problem(503,"SHOP_READ_DEADLINE");
            Entry entry=new Entry(value,softDeadline,started+ttl);
            Long filled=redis.execute(FILL,List.of(key+":lock",key),owner,
                json.writeValueAsString(entry),Long.toString(remaining));
            if(!Long.valueOf(1).equals(filled)) throw new Problem(503,"SHOP_REFRESH_SUPERSEDED");
            return entry;
        } finally { release(key,owner); }
    }
    private void release(String key,String owner) { redis.execute(RELEASE,List.of(key+":lock"),owner); }
    public Shop save(long id,String name,String description) {
        if(id<=0 || name==null || name.isBlank() || name.length()>200 || description==null || description.length()>10000)
            throw new Problem(400,"INVALID_SHOP");
        tx.run(()->{
            if(bloom!=null && db.queryForObject("SELECT COUNT(*) FROM ux_shop WHERE id=?",Integer.class,id)==0) bloom.beforeInsert(id);
            // MySQL row lock also serializes invalidation generation changes for this shop.
            db.update("INSERT INTO ux_shop(id,name,description,revision) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE name=?,description=?,revision=revision+1",id,name,description,name,description);
            trading.invalidate(key(id));
            return null;
        });
        drainInvalidations();
        return direct(id);
    }
    @Scheduled(fixedDelay=500)
    public void invalidationJob() { if(jobs) drainInvalidations(); }
    public void drainInvalidations() {
        var work=db.queryForList("SELECT cache_key,generation FROM ux_invalidation ORDER BY created_at LIMIT 100");
        for(var job:work) {
            String key=(String)job.get("cache_key");
            try {
                redis.execute(INVALIDATE,List.of(key,key+":lock"),INVALIDATION_CHANNEL);
                invalidateLocal(key);
                db.update("DELETE FROM ux_invalidation WHERE cache_key=? AND generation=?",key,job.get("generation"));
            } catch(Exception unavailable) { break; }
        }
    }
    @PreDestroy public void close() { rebuild.shutdownNow(); }
}
