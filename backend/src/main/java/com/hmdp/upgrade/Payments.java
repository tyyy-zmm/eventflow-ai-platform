package com.hmdp.upgrade;

import java.sql.Timestamp;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Payment orchestration with a durable simulated provider; no real-money integration. */
@Service
public class Payments {
    private final JdbcTemplate db;private final Transactions tx;private final Trading trading;
    @Value("${upgrade.jobs:true}") private boolean jobs;
    public Payments(JdbcTemplate db,Transactions tx,Trading trading) {this.db=db;this.tx=tx;this.trading=trading;}
    private void lock(String request,long user) {
        var r=trading.result(user,request);
        db.queryForList("SELECT id FROM ux_activity WHERE id=? FOR UPDATE",r.activityId());
    }
    public Map<String,Object> create(long user,String request) {
        return tx.run(()->{
            lock(request,user);
            var previous=db.queryForList("SELECT * FROM ux_payment WHERE request_id=?",request);
            if(!previous.isEmpty()) return previous.get(0);
            var order=trading.order(user,request);
            if(!"PENDING_CONFIRM".equals(order.get("state")) || !trading.now().isBefore(((Timestamp)order.get("confirm_until")).toInstant()))
                throw new Problem(409,"ORDER_NOT_PAYABLE");
            String id=Trading.uuid();
            db.update("INSERT INTO ux_payment(id,request_id,user_id,amount_cents,state,created_at) VALUES(?,?,?,?,'OPEN',CURRENT_TIMESTAMP(3))",id,request,user,order.get("price_cents"));
            return get(user,id);
        });
    }
    public Map<String,Object> get(long user,String payment) {
        var rows=db.queryForList("SELECT * FROM ux_payment WHERE id=? AND user_id=?",payment,user);
        if(rows.isEmpty()) throw new Problem(404,"PAYMENT_NOT_FOUND");
        var result=rows.get(0);
        result.put("receipts",db.queryForList("SELECT channel_id,amount_cents,outcome FROM ux_payment_receipt WHERE payment_id=? ORDER BY created_at,channel_id",payment));
        return result;
    }
    // Authenticated sandbox callback: channelId is a stable provider transaction identifier.
    public Map<String,Object> received(long user,String payment,String channelId,int amount) {
        if(channelId==null || !channelId.matches("[A-Za-z0-9_-]{8,64}")) throw new Problem(400,"INVALID_CHANNEL_ID");
        var initial=get(user,payment);
        return tx.run(()->{
            lock((String)initial.get("request_id"),user);
            db.queryForList("SELECT id FROM ux_payment WHERE id=? FOR UPDATE",payment);
            var p=get(user,payment);
            if(amount!=((Number)p.get("amount_cents")).intValue()) throw new Problem(400,"PAYMENT_AMOUNT_MISMATCH");
            var receipts=db.queryForList("SELECT * FROM ux_payment_receipt WHERE channel_id=?",channelId);
            if(!receipts.isEmpty()) {
                if(!payment.equals(receipts.get(0).get("payment_id"))) throw new Problem(409,"PAYMENT_RECEIPT_CONFLICT");
                return receipts.get(0);
            }
            var before=trading.order(user,(String)p.get("request_id"));
            boolean open="OPEN".equals(p.get("state")) && "PENDING_CONFIRM".equals(before.get("state"));
            var after=open?trading.paymentConfirm(user,(String)p.get("request_id")):before;
            boolean accepted=open && "CONFIRMED".equals(after.get("state"));
            String outcome=accepted?"APPLIED":"REFUND_PENDING";
            db.update("INSERT INTO ux_payment_receipt(channel_id,payment_id,amount_cents,outcome,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP(3))",channelId,payment,amount,outcome);
            if(accepted) db.update("UPDATE ux_payment SET state='PAID' WHERE id=?",payment);
            else {
                db.update("INSERT INTO ux_refund(channel_id,payment_id,amount_cents,next_at) VALUES(?,?,?,CURRENT_TIMESTAMP(3))",channelId,payment,amount);
                if(!"PAID".equals(p.get("state"))) db.update("UPDATE ux_payment SET state='REFUND_PENDING' WHERE id=?",payment);
            }
            return db.queryForMap("SELECT * FROM ux_payment_receipt WHERE channel_id=?",channelId);
        });
    }
    // Separate transactions model provider success followed by a possible local crash.
    void providerRefund(String channel,int amount) {
        tx.run(()->{
            var receipt=db.queryForMap("SELECT * FROM ux_payment_receipt WHERE channel_id=? FOR UPDATE",channel);
            if(!receipt.get("amount_cents").equals(amount)) throw new Problem(409,"REFUND_AMOUNT_MISMATCH");
            if(db.queryForObject("SELECT COUNT(*) FROM ux_sandbox_refund WHERE channel_id=?",Integer.class,channel)==0)
                db.update("INSERT INTO ux_sandbox_refund(channel_id,amount_cents,created_at) VALUES(?,?,CURRENT_TIMESTAMP(3))",channel,amount);
            return null;
        });
    }
    public void refunds() {
        for(var row:db.queryForList("SELECT * FROM ux_refund WHERE done_at IS NULL AND next_at<=CURRENT_TIMESTAMP(3) ORDER BY next_at LIMIT 100")) {
            String channel=(String)row.get("channel_id");
            try {
                providerRefund(channel,((Number)row.get("amount_cents")).intValue());
                tx.run(()->{
                    db.queryForList("SELECT id FROM ux_payment WHERE id=? FOR UPDATE",row.get("payment_id"));
                    db.update("UPDATE ux_refund SET done_at=CURRENT_TIMESTAMP(3) WHERE channel_id=? AND done_at IS NULL",channel);
                    db.update("UPDATE ux_payment_receipt SET outcome='REFUNDED' WHERE channel_id=?",channel);
                    if(db.queryForObject("SELECT COUNT(*) FROM ux_refund WHERE payment_id=? AND done_at IS NULL",Integer.class,row.get("payment_id"))==0)
                        db.update("UPDATE ux_payment SET state='REFUNDED' WHERE id=? AND state='REFUND_PENDING'",row.get("payment_id"));return null;
                });
            } catch(RuntimeException failure) {
                int attempts=((Number)row.get("attempts")).intValue();
                db.update("UPDATE ux_refund SET attempts=attempts+1,next_at=? WHERE channel_id=? AND done_at IS NULL",Timestamp.from(trading.now().plusSeconds(Math.min(60,1L<<Math.min(attempts,6)))),channel);
            }
        }
    }
    @Scheduled(fixedDelay=1000) public void scheduled() {if(jobs) refunds();}
}
