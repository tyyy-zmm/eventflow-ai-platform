package com.hmdp.upgrade;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static com.hmdp.upgrade.PipelineMetrics.Stage.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineMetricsTest {
    @SuppressWarnings("unchecked") Map<String,Object> stage(PipelineMetrics metrics,String name) {
        return (Map<String,Object>)((Map<String,Object>)metrics.snapshot().get("stages")).get(name);
    }
    @Test void recordsFailureWithoutChangingException() {
        var metrics=new PipelineMetrics();var failure=new IllegalStateException("injected");
        assertSame(failure,assertThrows(IllegalStateException.class,()->metrics.measure(ACCEPT_TX,()->{throw failure;})));
        assertEquals(1L,stage(metrics,"ACCEPT_TX").get("count"));
        assertEquals(1L,stage(metrics,"ACCEPT_TX").get("failures"));
    }
    @Test void fixedBucketsIncludeLongTailWithoutKeepingSamples() {
        var metrics=new PipelineMetrics();metrics.record(ORDER_TX,1_500_000,true);metrics.record(ORDER_TX,60_000_000_001L,true);
        var buckets=(List<?>)stage(metrics,"ORDER_TX").get("buckets");
        assertEquals(PipelineMetrics.UPPER_MS.length+1,buckets.size());
        assertEquals(1L,buckets.get(4));assertEquals(1L,buckets.get(buckets.size()-1));
    }
    @Test void concurrentUpdatesPreserveCountsAndTotals() throws Exception {
        var metrics=new PipelineMetrics();var pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Callable<Void>>();
            for(int i=0;i<4;i++) tasks.add(()->{for(int j=0;j<1000;j++) metrics.record(KAFKA_SEND,1_000_000,true);return null;});
            for(var result:pool.invokeAll(tasks)) result.get();
            assertEquals(4000L,stage(metrics,"KAFKA_SEND").get("count"));
            assertEquals(4000.0,stage(metrics,"KAFKA_SEND").get("sumMs"));
        } finally {pool.shutdownNow();}
    }
    @Test void futureClockSamplesAreNotPresentedAsZeroLatency() {
        var metrics=new PipelineMetrics();metrics.age(OUTBOX_AGE,System.currentTimeMillis()+60000);
        assertEquals(1L,metrics.snapshot().get("clockSkewSamples"));assertEquals(0L,stage(metrics,"OUTBOX_AGE").get("count"));
    }
}
