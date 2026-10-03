package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderProjectionApi {
    public record HistoryListing(java.util.List<OrderProjection.HistoryItem> items,int total,int page,String consistency) {}
    private final OrderProjection projection;
    public OrderProjectionApi(OrderProjection projection) {this.projection=projection;}
    @GetMapping("/v2/account/order-history")
    public HistoryListing list(HttpServletRequest request,@RequestParam(defaultValue="1") int page) {
        long user=((Auth.Identity)request.getAttribute("identity")).user();
        return new HistoryListing(projection.list(user,page),projection.count(user),page,"eventual");
    }
    @GetMapping("/v2/admin/order-projection") public Map<String,Object> status() {return projection.status();}
    @PostMapping("/v2/admin/order-projection/replay") public Map<String,Object> replay(@RequestParam(defaultValue="") String after) {
        if(after.length()>36) throw new Problem(400,"INVALID_CURSOR");
        return projection.replay(after);
    }
}
