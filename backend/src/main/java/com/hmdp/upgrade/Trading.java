package com.hmdp.upgrade;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class Trading {
    public record Request(String id, long userId, long activityId, String hash, String state,
                          String reason, Instant deadline) {}
    public record Event(int schemaVersion, String eventId, String requestId, long activityId) {}
    private final JdbcTemplate db;
    private final Transactions tx;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private Faults faults;
    public Trading(JdbcTemplate db, Transactions tx) { this.db=db; this.tx=tx; }
    static String uuid() { return UUID.randomUUID().toString(); }
    Instant now() { return db.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class).toInstant(); }
    static String hash(long activity) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(("v1:activity:"+activity).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private List<Request> requests(String suffix, Object... args) {
        return db.query("SELECT * FROM ux_request "+suffix, (r,n)->new Request(r.getString("id"),
            r.getLong("user_id"),r.getLong("activity_id"),r.getString("payload_hash"),r.getString("state"),
            r.getString("reason"),r.getTimestamp("deadline").toInstant()),args);
    }
    public Request existing(long user, String key, long activity) {
        validate(key, activity);
        List<Request> found=requests("WHERE user_id=? AND request_key=?",user,key);
        if(found.isEmpty()) return null;
        if(!found.get(0).hash().equals(hash(activity))) throw new Problem(409,"IDEMPOTENCY_CONFLICT");
        return found.get(0);
    }
    public Request result(long user, String id) {
        List<Request> found=requests("WHERE id=? AND user_id=?",id,user);
        if(found.isEmpty()) throw new Problem(404,"REQUEST_NOT_FOUND");
        return found.get(0);
    }
    static void validate(String key, long activity) {
        if(activity<=0 || key==null || !key.matches("[A-Za-z0-9_-]{8,64}")) throw new Problem(400,"INVALID_REQUEST");
    }
    private Map<String,Object> activity(long id) {
        var rows=db.queryForList("SELECT * FROM ux_activity WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new Problem(404,"ACTIVITY_NOT_FOUND");
        return rows.get(0);
    }
    private Instant instant(Map<String,Object> row,String field) { return ((Timestamp)row.get(field)).toInstant(); }
    public Request accept(long user, String key, long activity, boolean synchronous) {
        validate(key,activity);
        try {
            return tx.run(()->{
                var a=activity(activity);
                Request replay=existing(user,key,activity);
                if(replay!=null) return replay;
                Instant now=now();
                if(now.isBefore(instant(a,"starts_at")) || !now.isBefore(instant(a,"ends_at")))
                    throw new Problem(409,"ACTIVITY_CLOSED");
                String id=uuid();
                db.update("INSERT INTO ux_request(id,user_id,request_key,activity_id,payload_hash,state,created_at,deadline) VALUES(?,?,?,?,?,'ACCEPTED',?,?)",
                    id,user,key,activity,hash(activity),Timestamp.from(now),a.get("process_until"));
                if(synchronous) processLocked(id,activity,a);
                else db.update("INSERT INTO ux_outbox(id,request_id,activity_id,next_at,created_at) VALUES(?,?,?,?,?)",
                    uuid(),id,activity,Timestamp.from(now),Timestamp.from(now));
                return result(user,id);
            });
        } catch(DuplicateKeyException collision) {
            Request replay=existing(user,key,activity);
            if(replay==null) throw collision;
            return replay;
        }
    }
    public Request process(Event event) {
        return tx.run(()->{
            var a=activity(event.activityId());
            Integer match=db.queryForObject("SELECT COUNT(*) FROM ux_outbox WHERE id=? AND request_id=? AND activity_id=?",
                Integer.class,event.eventId(),event.requestId(),event.activityId());
            if(match==null || match!=1) throw new Problem(400,"EVENT_MISMATCH");
            return processLocked(event.requestId(),event.activityId(),a);
        });
    }
    private Request processLocked(String id,long activity,Map<String,Object> a) {
        var rows=requests("WHERE id=? AND activity_id=? FOR UPDATE",id,activity);
        if(rows.isEmpty()) throw new Problem(400,"EVENT_MISMATCH");
        Request r=rows.get(0);
        if(!r.state().equals("ACCEPTED")) return r;
        Instant now=now();
        if(!now.isBefore(r.deadline())) return finish(r,"EXPIRED","PROCESS_DEADLINE",now);
        if(db.queryForObject("SELECT COUNT(*) FROM ux_order WHERE activity_id=? AND user_id=?",Integer.class,activity,r.userId())>0) {
            return finish(r,"REJECTED","ALREADY_PURCHASED",now);
        }
        if(db.update("UPDATE ux_activity SET available=available-1 WHERE id=? AND available>0",activity)==0) {
            return finish(r,"REJECTED","SOLD_OUT",now);
        }
        db.update("INSERT INTO ux_order(id,request_id,activity_id,user_id,price_cents,state,confirm_until,created_at) VALUES(?,?,?,?,?,'PENDING_CONFIRM',?,?)",
            uuid(),id,activity,r.userId(),a.get("price_cents"),Timestamp.from(now.plusSeconds(300)),Timestamp.from(now));
        return finish(r,"SUCCEEDED",null,now);
    }
    private Request finish(Request request,String state,String reason,Instant now) {
        int changed=db.update("UPDATE ux_request SET state=?,reason=?,completed_at=? WHERE id=? AND state='ACCEPTED'",
            state,reason,Timestamp.from(now),request.id());
        if(changed==1) reservationAction(request.id(),"SUCCEEDED".equals(state)?"CONFIRM":
            "ALREADY_PURCHASED".equals(reason)?"RELEASE_KEEP":"RELEASE",now);
        return requests("WHERE id=?",request.id()).get(0);
    }
    private void reservationAction(String request,String action,Instant now) {
        db.update("""
            INSERT INTO ux_reservation_action(id,request_id,action,next_at,created_at)
            SELECT ?,?,?,?,? WHERE NOT EXISTS(
              SELECT 1 FROM ux_reservation_action WHERE request_id=? AND action=?
            )
            """,uuid(),request,action,Timestamp.from(now),Timestamp.from(now),request,action);
    }
    public Map<String,Object> order(long user,String request) {
        var rows=db.queryForList("SELECT * FROM ux_order WHERE request_id=? AND user_id=?",request,user);
        if(rows.isEmpty()) throw new Problem(404,"ORDER_NOT_FOUND");
        return rows.get(0);
    }
    public Map<String,Object> transition(long user,String request,String action) {
        if(!List.of("confirm","cancel","expire").contains(action)) throw new Problem(400,"INVALID_ACTION");
        Request r=result(user,request);
        return tx.run(()->{
            activity(r.activityId());
            var o=order(user,request);
            String state=(String)o.get("state");
            if(!state.equals("PENDING_CONFIRM")) return o;
            Instant now=now();
            boolean expired=!now.isBefore(instant(o,"confirm_until"));
            if(action.equals("expire") && !expired) return o;
            String next=expired ? "EXPIRED" : action.equals("confirm") ? "CONFIRMED" : "CANCELLED";
            int changed=db.update("UPDATE ux_order SET state=? WHERE id=? AND state='PENDING_CONFIRM'",next,o.get("id"));
            if(changed==1 && !next.equals("CONFIRMED")) {
                db.update("UPDATE ux_activity SET available=available+1 WHERE id=?",r.activityId());
                reservationAction(r.id(),"RESTORE",now);
                if(faults!=null) faults.hit("cancel-before-commit");
                invalidate("ux:sold:{"+r.activityId()+"}");
            }
            return order(user,request);
        });
    }
    // Called within the same business transaction; generation makes acknowledgement conditional.
    void invalidate(String key) {
        String gen=uuid();
        if(db.update("UPDATE ux_invalidation SET generation=?,created_at=CURRENT_TIMESTAMP(3) WHERE cache_key=?",gen,key)==0)
            db.update("INSERT INTO ux_invalidation(cache_key,generation,created_at) VALUES(?,?,CURRENT_TIMESTAMP(3))",key,gen);
    }
    public void expireRequests() {
        var pending=requests("WHERE state='ACCEPTED' AND deadline<=CURRENT_TIMESTAMP(3) ORDER BY deadline LIMIT 100");
        for(Request r:pending) tx.run(()->{
            activity(r.activityId());
            var locked=requests("WHERE id=? FOR UPDATE",r.id()).get(0);
            if(!now().isBefore(locked.deadline())) finish(locked,"EXPIRED","PROCESS_DEADLINE",now());
            return null;
        });
        var orders=db.queryForList("SELECT request_id,user_id FROM ux_order WHERE state='PENDING_CONFIRM' AND confirm_until<=CURRENT_TIMESTAMP(3) LIMIT 100");
        for(var o:orders) transition(((Number)o.get("user_id")).longValue(),(String)o.get("request_id"),"expire");
    }
    public Map<String,Object> invariants() {
        long badStock=db.queryForObject("SELECT COUNT(*) FROM ux_activity a WHERE available<0 OR available>capacity OR available+(SELECT COUNT(*) FROM ux_order o WHERE o.activity_id=a.id AND o.state IN ('PENDING_CONFIRM','CONFIRMED'))<>capacity",Long.class);
        long mismatched=db.queryForObject("SELECT COUNT(*) FROM ux_request r WHERE (state='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM ux_order o WHERE o.request_id=r.id)) OR (state<>'SUCCEEDED' AND EXISTS(SELECT 1 FROM ux_order o WHERE o.request_id=r.id))",Long.class);
        long pending=db.queryForObject("SELECT COUNT(*) FROM ux_request WHERE state='ACCEPTED'",Long.class);
        return Map.of("stockViolations",badStock,"stateViolations",mismatched,"pending",pending);
    }
}
