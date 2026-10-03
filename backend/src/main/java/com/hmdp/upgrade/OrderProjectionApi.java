package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderProjectionApi {
    private final OrderProjection projection;
    public OrderProjectionApi(OrderProjection projection) {this.projection=projection;}
    @GetMapping("/v2/account/order-history")
    public Map<String,Object> list(HttpServletRequest request,@RequestParam(defaultValue="1") int page) {
        long user=((Auth.Identity)request.getAttribute("identity")).user();
        return Map.of("items",projection.list(user,page),"page",page,"consistency","eventual");
    }
    @GetMapping("/v2/admin/order-projection") public Map<String,Object> status() {return projection.status();}
    @PostMapping("/v2/admin/order-projection/replay") public Map<String,Object> replay(@RequestParam(defaultValue="") String after) {
        if(after.length()>36) throw new Problem(400,"INVALID_CURSOR");
        return projection.replay(after);
    }
}
