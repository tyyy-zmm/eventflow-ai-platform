package com.hmdp.upgrade.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.upgrade.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PlanningTaskService {
    public record Task(String id,String status,Object result,String errorCode) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final PlanningEngine engine;
    private record Claim(String id,long user,String owner,String input,LocalDateTime deadline) {}
    public PlanningTaskService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,PlanningEngine engine) {
        this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(10);this.engine=engine;
    }
    public Task create(long user,String key,PlanningEngine.Input input) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,128}") || input==null) throw new Problem(400,"INVALID_PLAN");
        String payload=encode(input);
        String hash;
        try { hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
        return tx.execute(status -> {
            lockUser(user);
            var prior=db.queryForList("SELECT id,input_hash FROM ux_planning_task WHERE user_id=? AND request_key=?",user,key);
            if(!prior.isEmpty()) {
                if(!hash.equals(prior.get(0).get("input_hash"))) throw new Problem(409,"PLAN_KEY_CONFLICT");
                return get(user,prior.get(0).get("id").toString());
            }
            if(!engine.enabled()) throw new Problem(503,"PLANNING_DISABLED");
            if(db.queryForObject("SELECT COUNT(*) FROM ux_planning_task WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Long.class,user)>=2) {
                throw new Problem(429,"TOO_MANY_PLANS");
            }
            if(db.update("UPDATE ux_planning_queue_quota SET queued_count=queued_count+1 WHERE id=1 AND queued_count<queue_limit")!=1) {
                throw new Problem(503,"PLANNING_QUEUE_FULL");
            }
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO ux_planning_task(id,user_id,request_key,input_hash,input_json,deadline) VALUES(?,?,?,?,?,?)",
                id,user,key,hash,payload,Timestamp.valueOf(now().plusSeconds(60)));
            return get(user,id);
        });
    }
    public Task get(long user,String id) {
        var rows=db.queryForList("SELECT id,status,result_json,error_code FROM ux_planning_task WHERE id=? AND user_id=?",id,user);
        if(rows.isEmpty()) throw new Problem(404,"PLAN_NOT_FOUND");
        var row=rows.get(0);
        try { return new Task(id,row.get("status").toString(),row.get("result_json")==null?null:json.readTree(row.get("result_json").toString()),(String)row.get("error_code")); }
        catch(Exception e) { throw new IllegalStateException("Invalid persisted result"); }
    }
    public Task cancel(long user,String id) {
        return tx.execute(status -> {
            lockUser(user);
            lockTask(user,id);
            Task task=get(user,id);
            if(task.status().equals("CANCELLED")) return task;
            if(!task.status().equals("QUEUED") && !task.status().equals("RUNNING")) throw new Problem(409,"PLAN_TERMINAL");
            if(task.status().equals("QUEUED")) releaseQueue();
            db.update("UPDATE ux_planning_task SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
            return get(user,id);
        });
    }
    private void lockUser(long user) {
        db.queryForObject("SELECT queued_count FROM ux_planning_queue_quota WHERE id=1 FOR UPDATE",Integer.class);
        var rows=db.query("SELECT id FROM ux_account WHERE id=? FOR UPDATE",(rs,n)->rs.getLong(1),user);
        if(rows.isEmpty()) throw new Problem(404,"ACCOUNT_NOT_FOUND");
    }
    private void releaseQueue() {
        if(db.update("UPDATE ux_planning_queue_quota SET queued_count=queued_count-1 WHERE id=1 AND queued_count>0")!=1) throw new IllegalStateException("Queue accounting mismatch");
    }
    private void lockTask(long user,String id) {
        var rows=db.queryForList("SELECT id FROM ux_planning_task WHERE id=? AND user_id=? FOR UPDATE",id,user);
        if(rows.isEmpty()) throw new Problem(404,"PLAN_NOT_FOUND");
    }
    public boolean runOne() {
        if(!engine.enabled()) return false;
        Claim claim=tx.execute(status -> {
            db.queryForObject("SELECT queued_count FROM ux_planning_queue_quota WHERE id=1 FOR UPDATE",Integer.class);
            var rows=db.queryForList("SELECT id,user_id,input_json,deadline FROM ux_planning_task WHERE status='QUEUED' AND deadline>CURRENT_TIMESTAMP ORDER BY created_at,id LIMIT 1");
            if(rows.isEmpty()) return null;
            var row=rows.get(0); long user=((Number)row.get("user_id")).longValue(); lockUser(user);
            String owner=UUID.randomUUID().toString(),id=row.get("id").toString();
            lockTask(user,id);
            if(db.update("UPDATE ux_planning_task SET status='RUNNING',attempts=attempts+1,lease_owner=?,lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED' AND deadline>CURRENT_TIMESTAMP",owner,Timestamp.valueOf(now().plusSeconds(90)),id)!=1) return null;
            releaseQueue(); return new Claim(id,user,owner,row.get("input_json").toString(),((Timestamp)row.get("deadline")).toLocalDateTime());
        });
        if(claim==null) return false;
        String result=null,state="FAILED",error="ENGINE_FAILED";
        try {
            long remaining=java.time.Duration.between(now(),claim.deadline()).toMillis();
            if(remaining<=0) throw new IllegalStateException("Task expired");
            var output=engine.execute(json.readValue(claim.input(),PlanningEngine.Input.class),remaining); result=encode(output);
            state=output.result().status().equals("READY")?"SUCCEEDED":output.result().status().equals("FAILED")?"FAILED":"NEEDS_REFINEMENT";
            error=state.equals("FAILED")?"ENGINE_FAILED":null;
        } catch(Exception failure) { /* Do not persist model errors, keys, or private context. */ }
        final String r=result,s=state,e=error;
        tx.executeWithoutResult(status -> {
            lockUser(claim.user());
            lockTask(claim.user(),claim.id());
            db.update("UPDATE ux_planning_task SET status=?,result_json=?,error_code=?,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND status='RUNNING' AND lease_owner=? AND deadline>CURRENT_TIMESTAMP AND lease_until>CURRENT_TIMESTAMP",s,r,e,claim.id(),claim.owner());
        });
        return true;
    }
    public int expire(int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid limit");
        var rows=db.queryForList("SELECT id,user_id FROM ux_planning_task WHERE status IN ('QUEUED','RUNNING') "
            + "AND (deadline<=CURRENT_TIMESTAMP OR lease_until<=CURRENT_TIMESTAMP) ORDER BY deadline,id LIMIT ?",limit);
        int expired=0;
        for(var row:rows) expired+=tx.execute(status -> {
            lockUser(((Number)row.get("user_id")).longValue());
            var task=db.queryForMap("SELECT status,deadline,lease_until FROM ux_planning_task WHERE id=? FOR UPDATE",row.get("id"));
            String state=task.get("status").toString();
            if(!state.equals("QUEUED") && !state.equals("RUNNING")) return 0;
            LocalDateTime current=now();
            boolean timeout=!((Timestamp)task.get("deadline")).toLocalDateTime().isAfter(current);
            boolean lost=state.equals("RUNNING") && task.get("lease_until")!=null
                && !((Timestamp)task.get("lease_until")).toLocalDateTime().isAfter(current);
            if(!timeout && !lost) return 0;
            if(state.equals("QUEUED")) releaseQueue();
            return db.update("UPDATE ux_planning_task SET status=?,error_code=?,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                timeout?"TIMED_OUT":"FAILED",timeout?"DEADLINE":"WORKER_LOST",row.get("id"));
        });
        return expired;
    }
    private LocalDateTime now() { return db.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toLocalDateTime(); }
    private String encode(Object value) {
        try { String s=json.writeValueAsString(value); if(s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536) throw new IllegalArgumentException("Task too large"); return s; }
        catch(Exception e) { throw new Problem(400,"INVALID_PLAN"); }
    }
}
