package com.hmdp.upgrade;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheCircuitTest {
    @Test void failureThresholdThenHealthyProbesAndGradualRecovery() {
        AtomicLong clock=new AtomicLong();var circuit=new CacheCircuit(null,clock::get);
        for(int i=0;i<4;i++)circuit.record(1,false);assertTrue(circuit.permit());
        circuit.record(1,false);assertFalse(circuit.permit());
        for(int i=0;i<5;i++)circuit.probeResult(true);
        assertEquals("RECOVERING",circuit.status().get("state"));
        clock.set(11_000_000_000L);circuit.probeResult(true);assertTrue(circuit.permit());
        assertEquals("CLOSED",circuit.status().get("state"));
    }
    @Test void slowRedisOpensCircuitAndManualModeCannotAutoRecover() {
        var circuit=new CacheCircuit(null,()->0L);
        for(int i=0;i<20;i++)circuit.record(200_000_000L,true);
        assertFalse(circuit.permit());circuit.forceOpen(true);
        for(int i=0;i<10;i++)circuit.probeResult(true);
        assertEquals("OPEN",circuit.status().get("state"));
        circuit.forceOpen(false);for(int i=0;i<5;i++)circuit.probeResult(true);
        assertEquals("RECOVERING",circuit.status().get("state"));
    }
}
