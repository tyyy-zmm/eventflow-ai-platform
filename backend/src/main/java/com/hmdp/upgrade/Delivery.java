package com.hmdp.upgrade;

import static com.hmdp.upgrade.PipelineMetrics.Stage.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayDeque;
import org.springframework.kafka.support.SendResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Delivery {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private PipelineMetrics telemetry=new PipelineMetrics();
    private final JdbcTemplate db;
    private final Transactions tx;
    private final Trading trading;
    private final KafkaTemplate<String,String> kafka;
    private final ObjectMapper json;
    private final Admission admission;
    private final Reservations reservations;
    private final String topic;
    private final boolean jobs;
    @Value("${upgrade.relay-batch:20}") private int relayBatch=20;
    @Value("${upgrade.relay-window:1}") private int relayWindow=1;
    @Value("${upgrade.relay-delay-ms:100}") private long relayDelay=100;
    @Value("${upgrade.repair-delay-ms:1000}") private long repairDelay=1000;
    @jakarta.annotation.PostConstruct void validateSettings() {
        if(relayBatch<1 || relayBatch>500 || relayWindow<1 || relayWindow>8 || relayDelay<10 || repairDelay<10)
            throw new IllegalArgumentException("invalid bounded delivery settings");
    }
    Map<String,Object> settings() { return Map.of("batch",relayBatch,"sendWindow",relayWindow,"relayDelayMs",relayDelay,"repairDelayMs",repairDelay); }
    @Value("${upgrade.compact-transactions:true}") private boolean compactTransactions=true;
    private record Pending(Map<String,Object> row,CompletableFuture<SendResult<String,String>> sent) {}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private Faults faults;
    public Delivery(JdbcTemplate db,Transactions tx,Trading trading,KafkaTemplate<String,String> kafka,
        ObjectMapper json,Admission admission,Reservations reservations,@Value("${upgrade.topic}") String topic,@Value("${upgrade.jobs:true}") boolean jobs) {
        this.db=db;this.tx=tx;this.trading=trading;this.kafka=kafka;this.json=json;this.admission=admission;this.reservations=reservations;this.topic=topic;this.jobs=jobs;
    }
    public Map<String,Object> claim() {
        return telemetry.measure(OUTBOX_CLAIM,()->tx.run(()->{
            var rows=db.queryForList("SELECT * FROM ux_outbox WHERE sent_at IS NULL AND next_at<=CURRENT_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(3)) ORDER BY next_at LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(rows.isEmpty()) return null;
            var row=rows.get(0);
            String owner=Trading.uuid();
            db.update("UPDATE ux_outbox SET owner=?,lease_until=?,attempts=attempts+1 WHERE id=?",
                owner,Timestamp.from(trading.now().plusSeconds(15)),row.get("id"));
            row.put("owner",owner);
            return row;
        }));
    }
    public boolean acknowledge(String id,String owner) {
        return db.update("UPDATE ux_outbox SET sent_at=CURRENT_TIMESTAMP(3),owner=NULL,lease_until=NULL WHERE id=? AND owner=? AND sent_at IS NULL",id,owner)==1;
    }
    @Scheduled(fixedDelayString="${upgrade.relay-delay-ms:100}")
    public void relay() {
        if(!jobs) return;
        telemetry.measure(RELAY_CYCLE,()->{relayBatch();return null;});
    }
    private void relayBatch() {
        var pending=new ArrayDeque<Pending>();boolean failed=false;
        for(int i=0;i<relayBatch && !failed;i++) {
            Map<String,Object> row=claim();if(row==null) break;
            try { pending.addLast(publish(row)); }
            catch(Exception error) { retry(row,error);failed=true; }
            if(pending.size()>=relayWindow) failed=!complete(pending.removeFirst()) || failed;
        }
        // Already-published messages must each be acknowledged or made retryable.
        // No unbounded executor/queue; at most relayWindow futures are retained.
        while(!pending.isEmpty()) complete(pending.removeFirst());
    }
    private Pending publish(Map<String,Object> row) throws Exception {
        telemetry.age(OUTBOX_AGE,((Timestamp)row.get("created_at")).getTime());
        var event=new Trading.Event(1,(String)row.get("id"),(String)row.get("request_id"),((Number)row.get("activity_id")).longValue());
        String body=json.writeValueAsString(event);long began=System.nanoTime();
        try {
            var future=kafka.send(topic,""+event.activityId(),body);
            future.whenComplete((result,error)->telemetry.record(KAFKA_SEND,System.nanoTime()-began,error==null));
            return new Pending(row,future);
        } catch(RuntimeException error) {telemetry.record(KAFKA_SEND,System.nanoTime()-began,false);throw error;}
    }
    private boolean complete(Pending pending) {
        var row=pending.row();
        try {
            pending.sent().get(6,TimeUnit.SECONDS);
            if(faults!=null) faults.hit("send-before-mark");
            acknowledge((String)row.get("id"),(String)row.get("owner"));return true;
        } catch(Exception error) {retry(row,error);return false;}
    }
    private void retry(Map<String,Object> row,Exception error) {
        if(error instanceof InterruptedException) Thread.currentThread().interrupt();
        int attempt=((Number)row.get("attempts")).intValue();
        long delay=Math.min(60,1L<<Math.min(6,attempt));
        db.update("UPDATE ux_outbox SET owner=NULL,lease_until=NULL,next_at=? WHERE id=? AND owner=? AND sent_at IS NULL",
            Timestamp.from(trading.now().plusSeconds(delay)),row.get("id"),row.get("owner"));
    }
    @KafkaListener(topics="${upgrade.topic}",autoStartup="${upgrade.jobs:true}")
    public void consume(ConsumerRecord<String,String> record,Acknowledgment ack) throws Exception {
        Trading.Event event;
        try {
            event=json.readValue(record.value(),Trading.Event.class);
            if(event==null || event.schemaVersion()!=1 || event.activityId()<=0 || event.eventId()==null || event.requestId()==null
                || !Long.toString(event.activityId()).equals(record.key())) throw new IllegalArgumentException();
        } catch(Exception malformed) { isolate(record,"INVALID_EVENT");ack.acknowledge();return; }
        telemetry.age(KAFKA_RECORD_AGE,record.timestamp());
        Trading.Request result;
        try { result=trading.process(event); }
        catch(Problem invalid) {
            if(invalid.status!=400 && invalid.status!=404) throw invalid;
            isolate(record,invalid.code);ack.acknowledge();return;
        }
        String reason=compactTransactions?result.reason():telemetry.measure(SQL_POST_COMMIT_READ,()->(String)db.queryForMap("SELECT reason FROM ux_request WHERE id=?",event.requestId()).get("reason"));
        if("SOLD_OUT".equals(reason)) {
            try { admission.soldOut(event.activityId()); }
            catch(org.springframework.dao.DataAccessException unavailable) { /* Optional short-lived hint. */ }
        }
        // The transaction already persisted reservation actions. Redis repair runs
        // independently; an outage must not hold Kafka acknowledgement hostage.
        if(faults!=null) faults.hit("commit-before-ack");
        ack.acknowledge();
    }
    private void isolate(ConsumerRecord<String,String> record,String reason) {
        tx.run(()->{
            if(db.queryForObject("SELECT COUNT(*) FROM ux_poison WHERE topic=? AND partition_id=? AND offset_id=?",Integer.class,
                record.topic(),record.partition(),record.offset())==0)
                db.update("INSERT INTO ux_poison(id,topic,partition_id,offset_id,body,reason,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",
                    Trading.uuid(),record.topic(),record.partition(),record.offset(),record.value()==null?"null":record.value().substring(0,Math.min(record.value().length(),8192)),reason);
            return null;
        });
    }
    public List<Map<String,Object>> quarantined() {
        return db.queryForList("SELECT id,topic,partition_id,offset_id,reason,created_at FROM ux_poison ORDER BY created_at DESC LIMIT 100");
    }
    public void replay(long actor,String event) {
        tx.run(()->{
            if(db.update("UPDATE ux_outbox SET sent_at=NULL,owner=NULL,lease_until=NULL,next_at=CURRENT_TIMESTAMP(3) WHERE id=?",event)!=1)
                throw new Problem(404,"EVENT_NOT_FOUND");
            db.update("INSERT INTO ux_audit(id,actor,action,target,created_at) VALUES(?,?,'REPLAY',?,CURRENT_TIMESTAMP(3))",Trading.uuid(),actor,event);
            return null;
        });
    }
    @Scheduled(fixedDelay=1000)
    public void expire() {
        if(!jobs) return;
        trading.expireRequests();
        telemetry.measure(RECONCILE_CYCLE,()->reservations.reconcileStale());
    }
    @Scheduled(fixedDelayString="${upgrade.repair-delay-ms:1000}")
    public void repair() {
        if(jobs) telemetry.measure(REPAIR_CYCLE,()->reservations.drainActions());
    }

}
