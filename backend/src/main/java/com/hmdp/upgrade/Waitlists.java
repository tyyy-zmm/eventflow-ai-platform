package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
public class Waitlists {
    public record Entry(String id,long activityId,String state,int position,Instant createdAt,String requestId,
                        String offerTitle,String shopName,String imagePath,Integer priceCents) {}
    public record Listing(List<Entry> items,Instant serverTime) {}
    public record Join(long activityId) {}
    record Claim(String id,long activityId,long userId,String requestKey) {}
    private final JdbcTemplate db;
    private final Transactions tx;
    private final Reservations reservations;
    private final Admission admission;
    private final Trading trading;
    private final boolean jobs;

    Waitlists(JdbcTemplate db,Transactions tx,Reservations reservations,Admission admission,Trading trading,
              @Value("${upgrade.jobs:true}") boolean jobs) {
        this.db=db;this.tx=tx;this.reservations=reservations;this.admission=admission;this.trading=trading;this.jobs=jobs;
    }
    private Instant now() { return db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant(); }
    public Entry join(long user,long activity) {
        if(activity<=0) throw new Problem(400,"INVALID_REQUEST");
        var activities=db.queryForList("SELECT available,starts_at,ends_at FROM ux_activity WHERE id=?",activity);
        if(activities.isEmpty()) throw new Problem(404,"ACTIVITY_NOT_FOUND");
        var a=activities.get(0);Instant current=now();
        if(current.isBefore(((Timestamp)a.get("starts_at")).toInstant()) || !current.isBefore(((Timestamp)a.get("ends_at")).toInstant()))
            throw new Problem(409,"ACTIVITY_CLOSED");
        if(db.queryForObject("SELECT COUNT(*) FROM ux_request WHERE user_id=? AND activity_id=?",Integer.class,user,activity)>0)
            throw new Problem(409,"ALREADY_PURCHASED");
        int redisRemaining=reservations.remaining(activity);
        if(redisRemaining>0 || (redisRemaining<0 && ((Number)a.get("available")).intValue()>0)) throw new Problem(409,"STILL_AVAILABLE");
        String id=Trading.uuid(),key="wait_"+id.replace("-","");Instant created=now();
        try {
            db.update("INSERT INTO ux_waitlist(id,activity_id,user_id,state,request_key,created_at,updated_at) VALUES(?,?,?,'WAITING',?,?,?)",
                id,activity,user,key,Timestamp.from(created),Timestamp.from(created));
        } catch(DuplicateKeyException replay) {
            var existing=db.queryForList("SELECT id,state FROM ux_waitlist WHERE user_id=? AND activity_id=?",user,activity);
            if(existing.isEmpty()) throw replay;
            String state=(String)existing.get(0).get("state");id=(String)existing.get(0).get("id");
            if("CANCELLED".equals(state) || "EXPIRED".equals(state))
                db.update("UPDATE ux_waitlist SET state='WAITING',promoted_request_id=NULL,request_key=?,created_at=?,updated_at=? WHERE id=?",key,Timestamp.from(created),Timestamp.from(created),id);
        }
        return get(user,id);
    }
    public Listing list(long user) {
        var rows=db.query("""
            SELECT w.*,v.title offer_title,s.name shop_name,p.image_path,a.price_cents,
              CASE WHEN w.state='WAITING' THEN (SELECT COUNT(*) FROM ux_waitlist q WHERE q.activity_id=w.activity_id AND q.state='WAITING' AND (q.created_at<w.created_at OR (q.created_at=w.created_at AND q.id<=w.id))) ELSE 0 END position
            FROM ux_waitlist w JOIN ux_activity a ON a.id=w.activity_id
            LEFT JOIN ux_offer v ON v.activity_id=w.activity_id LEFT JOIN ux_shop s ON s.id=v.shop_id LEFT JOIN ux_storefront p ON p.shop_id=s.id
            WHERE w.user_id=? ORDER BY w.created_at DESC
            """,(r,n)->entry(r),user);
        return new Listing(rows,now());
    }
    public Entry get(long user,String id) {
        return list(user).items().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow(()->new Problem(404,"WAITLIST_NOT_FOUND"));
    }
    public Entry cancel(long user,String id) {
        int changed=db.update("UPDATE ux_waitlist SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND state='WAITING'",id,user);
        if(changed==0) {
            Entry current=get(user,id);
            if(!"CANCELLED".equals(current.state())) throw new Problem(409,"WAITLIST_TERMINAL");
        }
        return get(user,id);
    }
    private Entry entry(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Entry(r.getString("id"),r.getLong("activity_id"),r.getString("state"),r.getInt("position"),
            r.getTimestamp("created_at").toInstant(),r.getString("promoted_request_id"),r.getString("offer_title"),
            r.getString("shop_name"),r.getString("image_path"),r.getObject("price_cents")==null?null:r.getInt("price_cents"));
    }
    @Scheduled(fixedDelay=1000)
    public void promote() {
        if(!jobs) return;
        db.update("UPDATE ux_waitlist SET state='WAITING',updated_at=CURRENT_TIMESTAMP(3) WHERE state='PROMOTING' AND updated_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 30 SECOND)");
        for(int i=0;i<20;i++) {
            Claim claim=claim();if(claim==null) break;
            promote(claim);
        }
        db.update("UPDATE ux_waitlist w JOIN ux_activity a ON a.id=w.activity_id SET w.state='EXPIRED',w.updated_at=CURRENT_TIMESTAMP(3) WHERE w.state='WAITING' AND a.ends_at<=CURRENT_TIMESTAMP(3)");
    }
    private Claim claim() {
        return tx.run(()->{
            var rows=db.queryForList("""
                SELECT w.id,w.activity_id,w.user_id,w.request_key FROM ux_waitlist w
                JOIN ux_activity a ON a.id=w.activity_id
                WHERE w.state='WAITING' AND a.available>0 AND a.ends_at>CURRENT_TIMESTAMP(3)
                ORDER BY w.created_at,w.id LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
            if(rows.isEmpty()) return null;var row=rows.get(0);String id=(String)row.get("id");
            if(db.update("UPDATE ux_waitlist SET state='PROMOTING',updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND state='WAITING'",id)!=1) return null;
            return new Claim(id,((Number)row.get("activity_id")).longValue(),((Number)row.get("user_id")).longValue(),(String)row.get("request_key"));
        });
    }
    void promote(Claim claim) {
        boolean entered=false;Reservations.Result reservation=null;
        try {
            reservation=reservations.reserve(claim.activityId(),claim.userId(),claim.requestKey(),Instant.now().plusSeconds(5));
            if(reservation.decision()==Reservations.Decision.SOLD_OUT) { waiting(claim.id());return; }
            if(reservation.decision()==Reservations.Decision.ALREADY_PURCHASED) { terminal(claim.id(),"CANCELLED");return; }
            if(reservation.decision()==Reservations.Decision.REPLAY_RELEASED || reservation.decision()==Reservations.Decision.REPLAY_RESTORED) {
                Trading.Request prior=trading.existing(claim.userId(),claim.requestKey(),claim.activityId());
                if(prior==null) terminal(claim.id(),"EXPIRED");else promoted(claim,prior);
                return;
            }
            try { admission.enter(claim.userId(),claim.activityId());entered=true; }
            catch(RuntimeException rejected) {
                if(reservation.newReservation()) reservations.releaseNow(claim.activityId(),claim.userId(),claim.requestKey(),reservation.epoch());
                throw rejected;
            }
            Trading.Request request=trading.existing(claim.userId(),claim.requestKey(),claim.activityId());
            if(request==null) request=trading.acceptReserved(claim.userId(),claim.requestKey(),claim.activityId(),false,reservation.epoch());
            promoted(claim,request);
        } catch(RuntimeException failure) {
            try {
                Trading.Request accepted=trading.existing(claim.userId(),claim.requestKey(),claim.activityId());
                if(accepted!=null) {
                    promoted(claim,accepted);
                    return;
                }
            } catch(RuntimeException ignored) {}
            if(failure instanceof Problem && reservation!=null && reservation.newReservation()) try { reservations.releaseNow(claim.activityId(),claim.userId(),claim.requestKey(),reservation.epoch()); } catch(RuntimeException ignored) {}
            waiting(claim.id());
        } finally { if(entered) admission.leave(); }
    }
    private void promoted(Claim claim,Trading.Request request) {
        String state=("ACCEPTED".equals(request.state()) || "SUCCEEDED".equals(request.state()))?"PROMOTED":"EXPIRED";
        db.update("UPDATE ux_waitlist SET state=?,promoted_request_id=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND state='PROMOTING'",state,request.id(),claim.id());
    }
    private void waiting(String id) { db.update("UPDATE ux_waitlist SET state='WAITING',updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND state='PROMOTING'",id); }
    private void terminal(String id,String state) { db.update("UPDATE ux_waitlist SET state=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND state='PROMOTING'",state,id); }
}

@RestController
@RequestMapping("/v2")
class WaitlistApi {
    private final Waitlists service;
    WaitlistApi(Waitlists service) { this.service=service; }
    private long user(HttpServletRequest req) { return ((Auth.Identity)req.getAttribute("identity")).user(); }
    @PostMapping("/waitlists") Waitlists.Entry join(@RequestBody Waitlists.Join body,HttpServletRequest req) { return service.join(user(req),body.activityId()); }
    @GetMapping("/account/waitlists") Waitlists.Listing list(HttpServletRequest req) { return service.list(user(req)); }
    @PostMapping("/waitlists/{id}/cancel") Waitlists.Entry cancel(@PathVariable String id,HttpServletRequest req) { return service.cancel(user(req),id); }
}
