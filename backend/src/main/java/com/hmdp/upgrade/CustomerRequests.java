package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
public class CustomerRequests {
    public record Item(String id,String requestKey,long activityId,String state,String reason,Instant createdAt,Instant deadline,
                       String offerTitle,Long shopId,String shopName,String imagePath,Integer priceCents,String orderId,String orderState,Instant confirmUntil) {}
    public record Listing(List<Item> items,int total,int pendingCount,int page,Instant serverTime) {}
    public record Detail(Item item,Instant serverTime) {}
    private final JdbcTemplate db;
    private final Trading trading;
    public CustomerRequests(JdbcTemplate db,Trading trading) { this.db=db;this.trading=trading; }
    private static final String SELECT="""
        SELECT r.*,o.id AS order_id,o.state AS order_state,o.confirm_until,
               COALESCE(o.price_cents,a.price_cents) AS price_cents,v.title AS offer_title,v.shop_id,s.name AS shop_name,p.image_path
        FROM ux_request r JOIN ux_activity a ON a.id=r.activity_id
        LEFT JOIN ux_order o ON o.request_id=r.id
        LEFT JOIN ux_offer v ON v.activity_id=r.activity_id
        LEFT JOIN ux_shop s ON s.id=v.shop_id LEFT JOIN ux_storefront p ON p.shop_id=s.id
        """;
    static Item item(ResultSet r,int n) throws SQLException {
        Timestamp until=r.getTimestamp("confirm_until");
        return new Item(r.getString("id"),r.getString("request_key"),r.getLong("activity_id"),r.getString("state"),r.getString("reason"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("deadline").toInstant(),r.getString("offer_title"),
            r.getObject("shop_id")==null?null:r.getLong("shop_id"),r.getString("shop_name"),r.getString("image_path"),r.getInt("price_cents"),
            r.getString("order_id"),r.getString("order_state"),until==null?null:until.toInstant());
    }
    private Instant now() { return db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant(); }
    public Listing list(long user,int page,String state) {
        if(page<1 || page>500 || !List.of("","pending","confirmed","closed").contains(state)) throw new Problem(400,"INVALID_FILTER");
        refreshExpired(user);
        String filter=switch(state) {
            case "pending" -> " AND (r.state='ACCEPTED' OR o.state='PENDING_CONFIRM')";
            case "confirmed" -> " AND o.state='CONFIRMED'";
            case "closed" -> " AND (r.state IN ('REJECTED','EXPIRED') OR o.state IN ('CANCELLED','EXPIRED'))";
            default -> "";
        };
        var items=db.query(SELECT+" WHERE r.user_id=?"+filter+" ORDER BY r.created_at DESC,r.id DESC LIMIT 20 OFFSET ?",CustomerRequests::item,user,(page-1)*20);
        int total=db.queryForObject("SELECT COUNT(*) FROM ux_request r LEFT JOIN ux_order o ON o.request_id=r.id WHERE r.user_id=?"+filter,Integer.class,user);
        int pending=db.queryForObject("SELECT COUNT(*) FROM ux_request r LEFT JOIN ux_order o ON o.request_id=r.id WHERE r.user_id=? AND (r.state='ACCEPTED' OR o.state='PENDING_CONFIRM')",Integer.class,user);
        return new Listing(items,total,pending,page,now());
    }
    private void refreshExpired(long user) {
        var expired=db.queryForList("SELECT request_id FROM ux_order WHERE user_id=? AND state='PENDING_CONFIRM' AND confirm_until<=CURRENT_TIMESTAMP(3) LIMIT 20",String.class,user);
        for(String request:expired) trading.transition(user,request,"expire");
    }
    public Detail get(long user,String value,boolean byKey) {
        refreshExpired(user);
        var rows=db.query(SELECT+" WHERE r.user_id=? AND r."+(byKey?"request_key":"id")+"=?",CustomerRequests::item,user,value);
        if(rows.isEmpty()) throw new Problem(404,"REQUEST_NOT_FOUND");
        return new Detail(rows.get(0),now());
    }
}

@RestController
@RequestMapping("/v2/account/requests")
class CustomerRequestApi {
    private final CustomerRequests queries;
    CustomerRequestApi(CustomerRequests queries) { this.queries=queries; }
    private long user(HttpServletRequest request) { return ((Auth.Identity)request.getAttribute("identity")).user(); }
    @GetMapping public CustomerRequests.Listing list(HttpServletRequest req,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="") String state) {
        return queries.list(user(req),page,state);
    }
    @GetMapping("/by-key/{key}") public CustomerRequests.Detail byKey(HttpServletRequest req,@PathVariable String key) { return queries.get(user(req),key,true); }
    @GetMapping("/{id}") public CustomerRequests.Detail detail(HttpServletRequest req,@PathVariable String id) { return queries.get(user(req),id,false); }
}
