package com.hmdp.upgrade.planning;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.*;

@Component
public class PlanningWorker {
    private final PlanningTaskService tasks;
    private final MeterRegistry metrics;
    private final Semaphore slots=new Semaphore(2);
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    public PlanningWorker(PlanningTaskService tasks,MeterRegistry metrics) { this.tasks=tasks;this.metrics=metrics; }
    @Scheduled(fixedDelay=500) public void poll() {
        if(slots.tryAcquire()) {
            try { workers.execute(()->{
                try {
                    try {
                        if(tasks.runOne()) metrics.counter("life.planning.tasks", "result", "completed").increment();
                    } catch(RuntimeException failure) {
                        metrics.counter("life.planning.tasks", "result", "worker_error").increment();
                    }
                } finally { slots.release(); }
            }); }
            catch(RejectedExecutionException closed) { slots.release(); }
        }
    }
    @Scheduled(fixedDelay=5000) public void expire() { tasks.expire(100); }
    @PreDestroy public void close() {
        workers.shutdownNow();
        try { workers.awaitTermination(20,TimeUnit.SECONDS); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
