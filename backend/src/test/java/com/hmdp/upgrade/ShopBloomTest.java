package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import java.util.List;

class ShopBloomTest {
    @Test void insertedIdsHaveNoFalseNegativesAndFalsePositivesStayNearConfiguredRate() {
        var filter=new ShopBloom.Filter(10000,.01);
        for(long id=1;id<=10000;id++)filter.add(id);
        for(long id=1;id<=10000;id++)assertTrue(filter.contains(id));
        int positives=0;for(long id=10001;id<=110000;id++)if(filter.contains(id))positives++;
        assertTrue(positives<2000,"unexpected false positive rate: "+positives);
    }
    @Test @SuppressWarnings("unchecked") void missingEpochAndUnavailableRedisFailOpenButKnownNegativeIsRejected() {
        var db=mock(JdbcTemplate.class);var redis=mock(StringRedisTemplate.class);var values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);when(values.get(ShopBloom.EPOCH)).thenReturn("e1");
        when(db.queryForList(anyString(),eq(Long.class),anyLong())).thenReturn(List.of());
        var filter=new ShopBloom(db,redis,null);assertTrue(filter.mightContain(12));filter.rebuild();
        when(redis.execute(eq(ShopBloom.CHECK),anyList(),anyString(),anyString())).thenReturn(0L);
        assertFalse(filter.mightContain(12));
        when(redis.execute(eq(ShopBloom.CHECK),anyList(),anyString(),anyString())).thenReturn(1L);
        assertTrue(filter.mightContain(12),"another instance inserted the shop");
        when(redis.execute(eq(ShopBloom.CHECK),anyList(),anyString(),anyString())).thenReturn(-1L);
        assertTrue(filter.mightContain(12));assertEquals(0L,filter.metrics().get("ready"));
        filter.rebuild();when(redis.execute(eq(ShopBloom.CHECK),anyList(),anyString(),anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertTrue(filter.mightContain(12));
    }
}
