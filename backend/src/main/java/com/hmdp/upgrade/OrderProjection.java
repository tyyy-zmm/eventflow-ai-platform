package com.hmdp.upgrade;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** An eventually consistent read model. Inventory and authoritative orders stay in the trading DB. */
@Service
public class OrderProjection implements MeterBinder {
    public record HistoryItem(String id,String requestId,long activityId,int priceCents,String state,
                              java.time.Instant createdAt,java.time.Instant confirmUntil,Long shopId,
                              String shopName,String offerTitle,String imagePath) {}
    private final JdbcTemplate db;
    private final LongAdder failures=new LongAdder(),applied=new LongAdder(),checked=new LongAdder(),repaired=new LongAdder();
    private final AtomicReference<String> reconcileCursor=new AtomicReference<>("");
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
    public void scheduled() {try {drain();} catch(RuntimeException unavailable) {failures.increment();}}
    public int drain() {
        // One statement reads the source state together with its revision.
        var rows=db.queryForList("""
            SELECT o.*,p.revision,v.shop_id,v.title AS offer_title,s.name AS shop_name,f.image_path
            FROM ux_order_projection p JOIN ux_order o ON o.id=p.order_id
            LEFT JOIN ux_offer v ON v.activity_id=o.activity_id
            LEFT JOIN ux_shop s ON s.id=v.shop_id LEFT JOIN ux_storefront f ON f.shop_id=s.id
            WHERE p.applied_revision<p.revision ORDER BY p.changed_at,p.order_id LIMIT 100
            """);
        for(var row:rows) {
            apply(row);
            db.update("UPDATE ux_order_projection SET applied_revision=? WHERE order_id=? AND revision=? AND applied_revision<?",
                row.get("revision"),row.get("id"),row.get("revision"),row.get("revision"));
            applied.increment();
        }
        return rows.size();
    }
    void apply(Map<String,Object> r) {
        String target=table(((Number)r.get("user_id")).longValue());
        // Revision is assigned last: the other assignments compare against the old revision.
        db.update("INSERT INTO "+target+"(id,user_id,request_id,activity_id,price_cents,state,created_at,confirm_until,revision,shop_id,shop_name,offer_title,image_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) "
            +"ON DUPLICATE KEY UPDATE state=CASE WHEN revision<VALUES(revision) THEN VALUES(state) ELSE state END,confirm_until=CASE WHEN revision<VALUES(revision) THEN VALUES(confirm_until) ELSE confirm_until END,"
            +"shop_id=CASE WHEN revision<VALUES(revision) THEN VALUES(shop_id) ELSE shop_id END,shop_name=CASE WHEN revision<VALUES(revision) THEN VALUES(shop_name) ELSE shop_name END,"
            +"offer_title=CASE WHEN revision<VALUES(revision) THEN VALUES(offer_title) ELSE offer_title END,image_path=CASE WHEN revision<VALUES(revision) THEN VALUES(image_path) ELSE image_path END,revision=GREATEST(revision,VALUES(revision))",
            r.get("id"),r.get("user_id"),r.get("request_id"),r.get("activity_id"),r.get("price_cents"),r.get("state"),r.get("created_at"),r.get("confirm_until"),r.get("revision"),
            r.get("shop_id"),r.get("shop_name"),r.get("offer_title"),r.get("image_path"));
    }
    public List<HistoryItem> list(long user,int page) {
        if(page<1 || page>500) throw new Problem(400,"INVALID_FILTER");
        return db.query("SELECT id,request_id,activity_id,price_cents,state,created_at,confirm_until,shop_id,shop_name,offer_title,image_path FROM "+table(user)+" WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?",(r,n)->
            new HistoryItem(r.getString("id"),r.getString("request_id"),r.getLong("activity_id"),r.getInt("price_cents"),r.getString("state"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("confirm_until").toInstant(),r.getObject("shop_id")==null?null:r.getLong("shop_id"),
                r.getString("shop_name"),r.getString("offer_title"),r.getString("image_path")),user,(page-1)*20);
    }
    public int count(long user) {return db.queryForObject("SELECT COUNT(*) FROM "+table(user)+" WHERE user_id=?",Integer.class,user);}
    public Map<String,Object> status() {
        var result=db.queryForMap("SELECT COUNT(*) AS pending,MIN(changed_at) AS oldest FROM ux_order_projection WHERE applied_revision<revision");
        result.put("failures",failures.sum());result.put("applied",applied.sum());result.put("checked",checked.sum());result.put("repaired",repaired.sum());
        result.put("reconcileCursor",reconcileCursor.get());result.put("physicalDatabases",1);result.put("tables",32);return result;
    }
    // Bounded replay uses an ID cursor; safe to repeat and to run alongside new writes.
    public Map<String,Object> replay(String after) {
        var ids=db.queryForList("SELECT id FROM ux_order WHERE id>? ORDER BY id LIMIT 100",String.class,after);
        for(String id:ids) db.update("INSERT INTO ux_order_projection(order_id,revision,applied_revision,changed_at) VALUES(?,1,0,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE applied_revision=0,changed_at=CURRENT_TIMESTAMP(3)",id);
        return Map.of("count",ids.size(),"next",ids.isEmpty()?after:ids.get(ids.size()-1));
    }
    @Scheduled(fixedDelayString="${upgrade.projection-reconcile-delay-ms:5000}")
    public void reconcileScheduled() {try {reconcile();} catch(RuntimeException unavailable) {failures.increment();}}
    public int reconcile() {
        String after=reconcileCursor.get();
        var source=db.queryForList("SELECT id,user_id,state,price_cents,confirm_until FROM ux_order WHERE id>? ORDER BY id LIMIT 100",after);
        if(source.isEmpty()) {reconcileCursor.set("");return 0;}
        int fixes=0;
        for(var order:source) {
            checked.increment();long user=((Number)order.get("user_id")).longValue();
            var target=db.queryForList("SELECT state,price_cents,confirm_until FROM "+table(user)+" WHERE id=?",order.get("id"));
            boolean differs=target.isEmpty() || !java.util.Objects.equals(target.get(0).get("state"),order.get("state"))
                || ((Number)target.get(0).get("price_cents")).intValue()!=((Number)order.get("price_cents")).intValue()
                || !java.util.Objects.equals(target.get(0).get("confirm_until"),order.get("confirm_until"));
            if(differs) {
                String id=(String)order.get("id");
                if(db.update("UPDATE ux_order_projection SET revision=revision+1,changed_at=CURRENT_TIMESTAMP(3) WHERE order_id=?",id)==0)
                    db.update("INSERT INTO ux_order_projection(order_id,revision,applied_revision,changed_at) VALUES(?,1,0,CURRENT_TIMESTAMP(3))",id);
                repaired.increment();fixes++;
            }
        }
        reconcileCursor.set((String)source.get(source.size()-1).get("id"));return fixes;
    }
    long pending() {return db.queryForObject("SELECT COUNT(*) FROM ux_order_projection WHERE applied_revision<revision",Long.class);}
    @Override public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder("life.order.projection.events",applied,LongAdder::sum).tag("kind","applied").register(registry);
        FunctionCounter.builder("life.order.projection.events",checked,LongAdder::sum).tag("kind","checked").register(registry);
        FunctionCounter.builder("life.order.projection.events",repaired,LongAdder::sum).tag("kind","repaired").register(registry);
        FunctionCounter.builder("life.order.projection.events",failures,LongAdder::sum).tag("kind","failures").register(registry);
        Gauge.builder("life.order.projection.pending",this,OrderProjection::pending).register(registry);
    }
}
