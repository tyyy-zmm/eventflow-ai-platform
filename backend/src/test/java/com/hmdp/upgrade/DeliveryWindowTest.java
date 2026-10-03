package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryWindowTest {
    @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
    JdbcTemplate db=mock(JdbcTemplate.class);Trading trading=mock(Trading.class);
    List<String> acknowledged=new CopyOnWriteArrayList<>();
    Delivery sender(int count,int window) {
        AtomicInteger cursor=new AtomicInteger();
        var sender=new Delivery(db,mock(Transactions.class),trading,kafka,new ObjectMapper(),mock(Admission.class),mock(Reservations.class),"test",true) {
            @Override public Map<String,Object> claim() {
                int i=cursor.getAndIncrement();
                return i>=count?null:Map.of("id","event"+i,"request_id","request"+i,"activity_id",1L,"owner","owner"+i,
                    "created_at",Timestamp.from(Instant.now()),"attempts",0);
            }
            @Override public boolean acknowledge(String id,String owner) {acknowledged.add(id);return true;}
        };
        ReflectionTestUtils.setField(sender,"relayBatch",count);ReflectionTestUtils.setField(sender,"relayWindow",window);
        when(trading.now()).thenAnswer(call->Instant.now());return sender;
    }
    @Test void pipelineIsBoundedAndAcknowledgesEveryCompletedSend() throws Exception {
        var pending=new CopyOnWriteArrayList<CompletableFuture<SendResult<String,String>>>();
        var inflight=new AtomicInteger();var maximum=new AtomicInteger();
        when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(call->{
            var future=new CompletableFuture<SendResult<String,String>>();
            maximum.accumulateAndGet(inflight.incrementAndGet(),Math::max);
            future.whenComplete((value,error)->inflight.decrementAndGet());pending.add(future);return future;
        });
        var sender=sender(5,2);var pool=Executors.newSingleThreadExecutor();
        try {
            var running=pool.submit(sender::relay);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(pending.size()<2 && System.nanoTime()<deadline) Thread.sleep(5);
            assertEquals(2,pending.size());assertFalse(running.isDone());
            while(!running.isDone() && System.nanoTime()<deadline) {for(var future:pending) future.complete(null);Thread.sleep(5);}
            running.get(1,TimeUnit.SECONDS);assertEquals(5,acknowledged.size());assertEquals(2,maximum.get());
        } finally {for(var future:pending) future.complete(null);pool.shutdownNow();}
    }
    @Test void failedSendDoesNotLoseAlreadyPublishedOtherMessage() {
        var failed=new CompletableFuture<SendResult<String,String>>();failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(failed,CompletableFuture.completedFuture(null));
        sender(5,2).relay();
        assertEquals(List.of("event1"),acknowledged);
        verify(kafka,times(2)).send(anyString(),anyString(),anyString());
        verify(db).update(startsWith("UPDATE ux_outbox SET owner=NULL"),any(),eq("event0"),eq("owner0"));
    }
    @Test void invalidWindowsAreRejectedAtStartup() {
        var sender=sender(20,9);assertThrows(IllegalArgumentException.class,sender::validateSettings);
    }
}
