package com.hmdp.upgrade;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.core.StringRedisTemplate;

@Component
public class CacheCircuit {
    enum State { CLOSED, OPEN, RECOVERING }
    record Sample(long at,long nanos,boolean success) {}
    private final StringRedisTemplate redis;private final LongSupplier clock;
    private final ArrayDeque<Sample> samples=new ArrayDeque<>();
    private State state=State.CLOSED;private int consecutiveFailures,healthyProbes;private long openedAt,recoveryAt;
    private boolean manual;
    @org.springframework.beans.factory.annotation.Autowired
    public CacheCircuit(StringRedisTemplate redis) {this(redis,System::nanoTime);}
    CacheCircuit(StringRedisTemplate redis,LongSupplier clock) {this.redis=redis;this.clock=clock;}
    synchronized boolean permit() {return state==State.CLOSED || (state==State.RECOVERING && ThreadLocalRandom.current().nextInt(10)==0);}
    synchronized boolean degraded() {return state!=State.CLOSED;}
    synchronized void record(long nanos,boolean success) {
        long now=clock.getAsLong();samples.add(new Sample(now,nanos,success));
        while(samples.size()>1000 || (!samples.isEmpty() && now-samples.peek().at()>60_000_000_000L)) samples.remove();
        consecutiveFailures=success?0:consecutiveFailures+1;
        if(!success && state==State.RECOVERING) {open(now);return;}
        if(state!=State.CLOSED) return;
        long failures=samples.stream().filter(x->!x.success()).count();
        long slow=samples.stream().filter(x->x.nanos()>100_000_000L).count();
        if(consecutiveFailures>=5 || (samples.size()>=20 && (failures*100>samples.size()*5 || slow*100>samples.size()))) open(now);
    }
    private void open(long now) {state=State.OPEN;openedAt=now;healthyProbes=0;}
    @Scheduled(fixedDelay=1000) public void probe() {
        synchronized(this) {if(state==State.CLOSED || manual || clock.getAsLong()-openedAt<1_000_000_000L)return;}
        boolean success;long start=clock.getAsLong();
        try(var connection=redis.getConnectionFactory().getConnection()) {success="PONG".equals(connection.ping());}
        catch(RuntimeException unavailable) {success=false;}
        probeResult(success && clock.getAsLong()-start<=100_000_000L);
    }
    synchronized void probeResult(boolean success) {
        if(manual || state==State.CLOSED) return;
        long now=clock.getAsLong();
        if(!success) {open(now);return;}
        if(state==State.OPEN && ++healthyProbes>=5) {state=State.RECOVERING;recoveryAt=now;}
        else if(state==State.RECOVERING && now-recoveryAt>=10_000_000_000L) {state=State.CLOSED;consecutiveFailures=0;samples.clear();}
    }
    public synchronized void forceOpen(boolean enabled) {manual=enabled;if(enabled)open(clock.getAsLong());}
    public synchronized Map<String,Object> status() {return Map.of("state",state.name(),"manual",manual,"sampleCount",samples.size(),"consecutiveFailures",consecutiveFailures,"healthyProbes",healthyProbes);}
    synchronized int stateCode() {return state.ordinal();}
}
