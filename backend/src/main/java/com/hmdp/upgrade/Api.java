package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@RestController
@RequestMapping("/v2")
public class Api {
    public record Purchase(String requestId,long activityId) {}
    public record ExperimentResult(String state,String reason,boolean enteredDatabase,String requestId) {}
    public record ShopInput(String name,String description) {}
    private final Trading trading;
    private final Admission gate;
    private final ShopCache shops;
    private final Delivery delivery;
    private final JdbcTemplate db;
    private final BenchmarkReservation reservations;
    private final Reservations productionReservations;
    private final boolean benchmark;
    @org.springframework.beans.factory.annotation.Autowired
    private Faults faults;
    public Api(Trading trading,Admission gate,ShopCache shops,Delivery delivery,JdbcTemplate db,BenchmarkReservation reservations,
        Reservations productionReservations,
        @Value("${upgrade.benchmark:false}") boolean benchmark) {
        this.trading=trading;this.gate=gate;this.shops=shops;this.delivery=delivery;this.db=db;this.reservations=reservations;
        this.productionReservations=productionReservations;this.benchmark=benchmark;
    }
    private long user(HttpServletRequest req) { return ((Auth.Identity)req.getAttribute("identity")).user(); }
    @PostMapping("/requests")
    public ResponseEntity<?> purchase(@RequestBody Purchase body,HttpServletRequest http) {
        return submit(body,user(http),false);
    }
    private ResponseEntity<?> submit(Purchase body,long user,boolean sync) {
        Reservations.Result reservation;
        try { reservation=productionReservations.reserve(body.activityId(),user,body.requestId(),java.time.Instant.now().plusSeconds(5)); }
        catch(org.springframework.dao.DataAccessException redisUnavailable) {
            var replay=trading.existing(user,body.requestId(),body.activityId());
            if(replay!=null) return response(replay);
            throw new Problem(503,"RESERVATION_UNAVAILABLE");
        }
        if(reservation.decision()==Reservations.Decision.ALREADY_PURCHASED) throw new Problem(409,"ALREADY_PURCHASED");
        if(reservation.decision()==Reservations.Decision.SOLD_OUT) throw new Problem(409,"SOLD_OUT");
        if(reservation.decision()==Reservations.Decision.REPLAY_RELEASED || reservation.decision()==Reservations.Decision.REPLAY_RESTORED) {
            var replay=trading.existing(user,body.requestId(),body.activityId());
            if(replay!=null) return response(replay);
            throw new Problem(409,"REQUEST_TERMINAL");
        }
        if(reservation.newReservation()) {
            try { gate.enter(user,body.activityId()); }
            catch(RuntimeException rejected) {
                try { productionReservations.releaseNow(body.activityId(),user,body.requestId(),false); } catch(RuntimeException ignored) {}
                throw rejected;
            }
        }
        try {
            if(faults!=null) faults.hit("reserve-before-db");
            var accepted=trading.accept(user,body.requestId(),body.activityId(),sync);
            faults.hit("accept-before-response");
            return response(accepted);
        }
        catch(Problem rejected) {
            if(reservation.newReservation()) try { productionReservations.releaseNow(body.activityId(),user,body.requestId(),false); } catch(RuntimeException ignored) {}
            throw rejected;
        }
        finally { if(reservation.newReservation()) gate.leave(); }
    }
    private ResponseEntity<?> response(Trading.Request r) {
        return ResponseEntity.status(r.state().equals("ACCEPTED")?202:200).body(r);
    }
    @GetMapping("/requests/{id}") public Object result(@PathVariable String id,HttpServletRequest req) { return trading.result(user(req),id); }
    @GetMapping("/requests/{id}/order") public Object order(@PathVariable String id,HttpServletRequest req) { return trading.order(user(req),id); }
    @PostMapping("/requests/{id}/{action:confirm|cancel}")
    public Object change(@PathVariable String id,@PathVariable String action,HttpServletRequest req) {
        return trading.transition(user(req),id,action);
    }
    @GetMapping("/shops/{id}") public Object shop(@PathVariable long id) {
        var result=shops.get(id);if(result==null) throw new Problem(404,"SHOP_NOT_FOUND");return result;
    }
    @PutMapping("/admin/shops/{id}") public Object save(@PathVariable long id,@RequestBody ShopInput body) { return shops.save(id,body.name(),body.description()); }
    @GetMapping("/admin/status") public Object status() { return Map.of("invariants",trading.invariants(),"cache",shops.metrics(),"reservations",productionReservations.metrics()); }
    @GetMapping("/admin/quarantine") public Object quarantine() { return delivery.quarantined(); }
    @PostMapping("/admin/events/{id}/replay") public Object replay(@PathVariable String id,HttpServletRequest req) { delivery.replay(user(req),id);return Map.of("queued",true); }
    @PostMapping("/benchmark/sync") public Object synchronous(@RequestBody Purchase body,HttpServletRequest req) {
        if(!benchmark) throw new Problem(404,"NOT_FOUND");return submit(body,user(req),true);
    }
    @PostMapping("/benchmark/mysql-first") public Object mysqlFirst(@RequestBody Purchase body,HttpServletRequest req) {
        if(!benchmark) throw new Problem(404,"NOT_FOUND");
        return experiment(trading.accept(user(req),body.requestId(),body.activityId(),true));
    }
    @PostMapping("/benchmark/redis-first") public Object redisFirst(@RequestBody Purchase body,HttpServletRequest req) {
        if(!benchmark) throw new Problem(404,"NOT_FOUND");
        var decision=reservations.reserve(body.activityId(),user(req),body.requestId());
        if(decision!=BenchmarkReservation.Decision.ADMITTED)
            return new ExperimentResult("REJECTED",decision.name(),false,null);
        return experiment(trading.accept(user(req),body.requestId(),body.activityId(),true));
    }
    private ExperimentResult experiment(Trading.Request request) {
        return new ExperimentResult(request.state(),request.reason(),true,request.id());
    }
    @GetMapping("/benchmark/direct/{id}") public Object direct(@PathVariable long id) {
        if(!benchmark) throw new Problem(404,"NOT_FOUND");return shops.direct(id);
    }
    @GetMapping("/benchmark/ttl/{id}") public Object ttl(@PathVariable long id) {
        if(!benchmark) throw new Problem(404,"NOT_FOUND");return shops.simpleTtl(id);
    }
    @PostMapping("/admin/fixtures/{id}") public Object fixture(@PathVariable long id,
        @RequestParam(defaultValue="10000") int stock) {
        if(!benchmark || id<100000) throw new Problem(404,"NOT_FOUND");
        if(stock<0 || stock>1000000) throw new Problem(400,"INVALID_STOCK");
        db.update("INSERT INTO ux_activity(id,capacity,available,price_cents,starts_at,ends_at,process_until) VALUES(?,?,?,990,DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 MINUTE),DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 2 HOUR),DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 3 HOUR))",id,stock,stock);
        reservations.prepare(id,stock);
        productionReservations.prepare(id,stock);
        shops.save(id,"Synthetic merchant "+id,"Local benchmark fixture, no real customer data");
        return Map.of("id",id,"stock",stock);
    }
    @GetMapping("/admin/fixtures/{id}/reservation") public Object fixtureReservation(@PathVariable long id) {
        if(!benchmark || id<100000) throw new Problem(404,"NOT_FOUND");
        return Map.of("remaining",productionReservations.remaining(id),"pending",productionReservations.pending(id));
    }
}
