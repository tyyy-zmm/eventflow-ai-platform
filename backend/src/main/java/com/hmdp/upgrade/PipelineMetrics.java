package com.hmdp.upgrade;

import java.util.*;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Fixed stages and buckets: no request/user/activity labels or unbounded samples. */
@Component
public class PipelineMetrics {
    enum Stage { RESERVE, ADMISSION, ACCEPT_TX, ACCEPT_LOCK_QUERY, ORDER_TX, ORDER_LOCK_QUERY,
        ACCEPT_LOCK_HELD, ORDER_LOCK_HELD, SQL_CLOCK, SQL_REQUEST_READ, SQL_EPOCH_CHECK, SQL_REQUEST_INSERT, SQL_OUTBOX_INSERT, SQL_EVENT_CHECK, SQL_DUPLICATE_CHECK, SQL_STOCK_UPDATE, SQL_ORDER_INSERT, SQL_REQUEST_FINISH, SQL_ACTION_INSERT, SQL_POST_COMMIT_READ, RECONCILE_ACTIVITY, MAINTENANCE_LOCK_QUERY, OUTBOX_CLAIM, OUTBOX_AGE, KAFKA_SEND, KAFKA_RECORD_AGE,
        RESERVATION_APPLY, RESERVATION_AGE, RELAY_CYCLE, REPAIR_CYCLE, RECONCILE_CYCLE }
    static final double[] UPPER_MS={.1,.25,.5,1,2,5,10,25,50,100,250,500,1000,2000,5000,10000,30000,60000};
    private static final class Histogram {
        final LongAdder count=new LongAdder(),failures=new LongAdder(),nanos=new LongAdder();
        final AtomicLongArray buckets=new AtomicLongArray(UPPER_MS.length+1);
    }
    private final EnumMap<Stage,Histogram> histograms=new EnumMap<>(Stage.class);
    private final LongAdder clockSkews=new LongAdder();
    public PipelineMetrics() { for(var stage:Stage.values()) histograms.put(stage,new Histogram()); }
    <T> T measure(Stage stage,Supplier<T> work) {
        long started=System.nanoTime();boolean success=false;
        try { T value=work.get();success=true;return value; }
        finally { record(stage,System.nanoTime()-started,success); }
    }
    void untilTransactionCompletion(Stage stage) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return;
        long began=System.nanoTime();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    record(stage,System.nanoTime()-began,status==STATUS_COMMITTED);
                }
            });
    }
    void record(Stage stage,long nanos,boolean success) {
        var h=histograms.get(stage);long elapsed=Math.max(0,nanos);double ms=elapsed/1_000_000.0;int bucket=0;
        while(bucket<UPPER_MS.length && ms>UPPER_MS[bucket]) bucket++;
        h.buckets.incrementAndGet(bucket);h.nanos.add(elapsed);if(!success) h.failures.increment();h.count.increment();
    }
    void age(Stage stage,long epochMs) {
        if(epochMs<0) return;
        long age=System.currentTimeMillis()-epochMs;
        if(age<0) {clockSkews.increment();return;}
        record(stage,age*1_000_000L,true);
    }
    public Map<String,Object> snapshot() {
        var stages=new LinkedHashMap<String,Object>();
        histograms.forEach((stage,h)->{
            var buckets=new ArrayList<Long>();for(int i=0;i<h.buckets.length();i++) buckets.add(h.buckets.get(i));
            stages.put(stage.name(),Map.of("count",h.count.sum(),"failures",h.failures.sum(),"sumMs",h.nanos.sum()/1_000_000.0,"buckets",buckets));
        });
        return Map.of("stages",stages,"bucketUpperMs",UPPER_MS.clone(),"overflowBucket","greater than last bound",
            "clockSkewSamples",clockSkews.sum(),"scope","per-instance attempt counters since startup; snapshots are approximate under concurrency");
    }
}
