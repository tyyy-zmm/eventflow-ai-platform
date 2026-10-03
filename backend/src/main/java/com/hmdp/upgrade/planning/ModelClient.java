package com.hmdp.upgrade.planning;

import com.fasterxml.jackson.databind.JsonNode;

public interface ModelClient {
    boolean enabled();
    default String mode() { return enabled()?"deepseek":"disabled"; }
    JsonNode complete(String role,Object payload,Budget budget);
    record CallDetails(int httpStatus, String rawResponse, JsonNode usage) {}
    default CallDetails takeLastCallDetails() { return null; }
    final class Budget {
        private final long deadline;
        public Budget() { this(60000); }
        public Budget(long millis) { deadline=System.nanoTime()+Math.min(60000,Math.max(1,millis))*1_000_000L; }
        private int calls;
        private int tokens;
        private boolean awaitingUsage;
        public synchronized void beforeCall() {
            if(awaitingUsage || Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline || calls>=5 || tokens>=12000) throw new IllegalStateException("Model budget exhausted or usage unknown");
            calls++;
            awaitingUsage=true;
        }
        public synchronized void usage(int used) {
            if(used<0) throw new IllegalStateException("Usage unknown");
            tokens=Math.addExact(tokens,used);
            awaitingUsage=false;
            if(tokens>12000) throw new IllegalStateException("Token budget exhausted");
        }
        public int calls() { return calls; }
        public int tokens() { return tokens; }
        public Integer knownTokens() { return awaitingUsage?null:tokens; }
        public long remainingMillis() { return Math.max(1,(deadline-System.nanoTime())/1_000_000); }
    }
}
