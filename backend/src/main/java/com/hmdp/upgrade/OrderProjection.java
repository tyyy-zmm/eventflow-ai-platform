package com.hmdp.upgrade;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** An eventually consistent read model. Inventory and authoritative orders stay in the trading DB. */
@Service
public class OrderProjection {
    private final JdbcTemplate db;
    private final LongAdder failures=new LongAdder();
    public OrderProjection(JdbcTemplate db) {this.db=db;}
    static int shard(long user) {return (int)Math.floorMod(user,32);}
    static String table(long user) {return "ux_order_read_"+String.format(java.util.Locale.ROOT,"%02d",shard(user));}
    // Called only after an order mutation, inside its transaction and activity lock.
    void changed(String id) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Projection intent requires the order transaction");
        if(db.update("UPDATE ux_order_projection SET revision=revision+1,changed_at=CURRENT_TIMESTAMP(3) WHERE order_id=?",id)==0)
            db.update("INSERT INTO ux_order_projection(order_id,revision,applied_revision,changed_at) VALUES(?,1,0,CURRENT_TIMESTAMP(3))",id);
    }
    @Scheduled(fixedDelayString="${upgrade.projection-delay-ms:1000}")
    public void scheduled() {try {drain();} catch(org.springframework.dao.DataAccessException unavailable) {failures.increment();}}
    public int drain() {
        // One statement reads the source state together with its revision.
        var rows=db.queryForList("SELECT o.*,p.revision FROM ux_order_projection p JOIN ux_order o ON o.id=p.order_id WHERE p.applied_revision<p.revision ORDER BY p.changed_at,p.order_id LIMIT 100");
        for(var row:rows) {
            apply(row);
            db.update("UPDATE ux_order_projection SET applied_revision=? WHERE order_id=? AND revision=? AND applied_revision<?",
                row.get("revision"),row.get("id"),row.get("revision"),row.get("revision"));
        }
        return rows.size();
    }
    void apply(Map<String,Object> r) {
        String target=table(((Number)r.get("user_id")).longValue());
        // Revision is assigned last: the other assignments compare against the old revision.
        db.update("INSERT INTO "+target+"(id,user_id,request_id,activity_id,price_cents,state,created_at,confirm_until,revision) VALUES(?,?,?,?,?,?,?,?,?) "
            +"ON DUPLICATE KEY UPDATE state=CASE WHEN revision<VALUES(revision) THEN VALUES(state) ELSE state END,confirm_until=CASE WHEN revision<VALUES(revision) THEN VALUES(confirm_until) ELSE confirm_until END,revision=GREATEST(revision,VALUES(revision))",
            r.get("id"),r.get("user_id"),r.get("request_id"),r.get("activity_id"),r.get("price_cents"),r.get("state"),r.get("created_at"),r.get("confirm_until"),r.get("revision"));
    }
    public List<Map<String,Object>> list(long user,int page) {
        if(page<1 || page>500) throw new Problem(400,"INVALID_FILTER");
        return db.queryForList("SELECT id,request_id,activity_id,price_cents,state,created_at,confirm_until FROM "+table(user)+" WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?",user,(page-1)*20);
    }
    public Map<String,Object> status() {
        var result=db.queryForMap("SELECT COUNT(*) AS pending,MIN(changed_at) AS oldest FROM ux_order_projection WHERE applied_revision<revision");
        result.put("failures",failures.sum());result.put("physicalDatabases",1);result.put("tables",32);return result;
    }
    // Bounded replay uses an ID cursor; safe to repeat and to run alongside new writes.
    public Map<String,Object> replay(String after) {
        var ids=db.queryForList("SELECT id FROM ux_order WHERE id>? ORDER BY id LIMIT 100",String.class,after);
        for(String id:ids) db.update("INSERT INTO ux_order_projection(order_id,revision,applied_revision,changed_at) VALUES(?,1,0,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE applied_revision=0,changed_at=CURRENT_TIMESTAMP(3)",id);
        return Map.of("count",ids.size(),"next",ids.isEmpty()?after:ids.get(ids.size()-1));
    }
}
