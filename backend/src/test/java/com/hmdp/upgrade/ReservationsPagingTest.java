package com.hmdp.upgrade;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationsPagingTest {
    JdbcTemplate db=mock(JdbcTemplate.class);
    StringRedisTemplate redis=mock(StringRedisTemplate.class);
    Trading trading=mock(Trading.class);
    @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
    @SuppressWarnings("unchecked") ZSetOperations<String,String> sorted=mock(ZSetOperations.class);
    Reservations service() {
        when(redis.opsForValue()).thenReturn(values);when(redis.opsForZSet()).thenReturn(sorted);
        when(values.get(anyString())).thenReturn("epoch");
        return new Reservations(redis,db,trading,mock(Transactions.class));
    }
    @Test void cursorWrapsWithoutLoadingAllActivities() {
        var r=service();ReflectionTestUtils.setField(r,"reconcilePageSize",2);
        when(db.queryForList(anyString(),eq(Long.class),eq(0L),eq(2))).thenReturn(List.of(10L,20L));
        when(db.queryForList(anyString(),eq(Long.class),eq(20L),eq(2))).thenReturn(List.of(30L));
        when(db.queryForList(anyString(),eq(Long.class),eq(30L),eq(2))).thenReturn(List.of());
        r.reconcileStale();r.reconcileStale();r.reconcileStale();r.reconcileStale();
        assertEquals(1L,r.settings().get("completedSweeps"));assertEquals(5L,r.settings().get("scannedActivities"));
        verify(db,times(2)).queryForList(contains("activity_id>? ORDER BY activity_id LIMIT ?"),eq(Long.class),eq(0L),eq(2));
    }
    @Test void actionBudgetDoesNotSkipUnvisitedActivity() {
        var r=service();ReflectionTestUtils.setField(r,"reconcileBudget",2);
        when(db.queryForList(anyString(),eq(Long.class),eq(0L),eq(32))).thenReturn(List.of(10L,20L));
        when(db.queryForList(anyString(),eq(Long.class),eq(10L),eq(32))).thenReturn(List.of(20L));
        when(sorted.rangeByScore(eq("ux:reserve:{10}:pending"),eq(0.0),anyDouble(),eq(0L),eq(2L)))
            .thenReturn(new LinkedHashSet<>(List.of("1:request001","2:request002")));
        r.reconcileStale();r.reconcileStale();
        verify(trading,times(2)).adjudicateReservation(anyLong(),anyString(),eq(10L),eq("epoch"));
        verify(values).get("ux:reserve:{20}:epoch");assertEquals(2L,r.settings().get("scannedActivities"));
    }
    @Test void invalidBoundsFailBeforeScheduling() {
        var r=service();ReflectionTestUtils.setField(r,"reconcilePageSize",0);
        assertThrows(IllegalArgumentException.class,r::validateSettings);
    }
}
