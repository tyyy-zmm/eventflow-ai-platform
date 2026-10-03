package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopCacheTest {
    ShopCache cache;
    StringRedisTemplate redis;
    ValueOperations<String,String> values;
    ObjectMapper json=new ObjectMapper();
    AtomicInteger reads=new AtomicInteger();
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:cache_test","sa",""));
        cache=new ShopCache(db,null,null,redis,json,5000,15000,false) {
            @Override public Shop direct(long id) { reads.incrementAndGet();return new Shop(id,"database","",2); }
        };
    }
    @AfterEach void close() { cache.close(); }
    String entry(String name,long deadline) throws Exception {
        return json.writeValueAsString(new ShopCache.Entry(new ShopCache.Shop(1,name,"",1),deadline,deadline));
    }
    @Test void secondReadUsesLocalCacheWithoutRedis() throws Exception {
        when(values.get(ShopCache.key(1))).thenReturn(entry("cached",System.currentTimeMillis()+5000));
        assertEquals("cached",cache.get(1).name());assertEquals("cached",cache.get(1).name());
        verify(values,times(1)).get(ShopCache.key(1));assertEquals(1L,cache.metrics().get("localHits"));
    }
    @Test void invalidationDuringLoadPreventsStaleLocalRefill() throws Exception {
        String old=entry("old",System.currentTimeMillis()+5000),fresh=entry("new",System.currentTimeMillis()+5000);
        var loaded=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        when(values.get(ShopCache.key(1))).thenAnswer(call->{
            if(calls.getAndIncrement()==0) {loaded.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return old;}
            return fresh;
        });
        var executor=Executors.newSingleThreadExecutor();
        try {
            var pending=executor.submit(()->cache.get(1));assertTrue(loaded.await(2,TimeUnit.SECONDS));
            cache.invalidateLocal(ShopCache.key(1));release.countDown();assertEquals("old",pending.get().name());
            assertEquals("new",cache.get(1).name());assertEquals(0L,cache.metrics().get("localHits"));
        } finally { release.countDown();executor.shutdownNow(); }
    }
    @Test void legacyPayloadIsNeverPromotedToLocalCache() throws Exception {
        when(values.get(ShopCache.key(1))).thenReturn(json.writeValueAsString(new ShopCache.Entry(new ShopCache.Shop(1,"legacy","",1),System.currentTimeMillis()+5000)));
        cache.get(1);cache.get(1);verify(values,times(2)).get(ShopCache.key(1));
    }
    @Test void reconnectClearsLocalEntries() throws Exception {
        when(values.get(ShopCache.key(1))).thenReturn(entry("old",System.currentTimeMillis()+5000));cache.get(1);
        new CacheNotifications.Listener(cache).onChannelSubscribed(new byte[0],1);
        when(values.get(ShopCache.key(1))).thenReturn(entry("new",System.currentTimeMillis()+5000));
        assertEquals("new",cache.get(1).name());
    }
    @Test void redisFailureUsesBoundedFallbackWithoutCachingResult() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("test outage"));
        int rejected=0;
        for(int i=0;i<100;i++) {
            try { assertEquals("database",cache.get(1).name()); }
            catch(Problem busy) { assertEquals("SHOP_FALLBACK_BUSY",busy.code);rejected++; }
        }
        assertTrue(rejected>0);assertTrue(reads.get()<100);assertEquals(0L,cache.metrics().get("localSize"));
    }
}
