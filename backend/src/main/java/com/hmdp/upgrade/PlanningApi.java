package com.hmdp.upgrade;

import com.hmdp.upgrade.planning.PlanningEngine;
import com.hmdp.upgrade.planning.PlanningTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/planning/tasks")
public class PlanningApi {
    public record CreatePlan(String requestKey, PlanningEngine.Input input) {}
    private final PlanningTaskService tasks;

    public PlanningApi(PlanningTaskService tasks) { this.tasks=tasks; }
    private long user(HttpServletRequest request) {
        return ((Auth.Identity)request.getAttribute("identity")).user();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreatePlan body,HttpServletRequest request) {
        if(body==null) throw new Problem(400,"INVALID_PLAN");
        return ResponseEntity.accepted().body(tasks.create(user(request),body.requestKey(),body.input()));
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable String id,HttpServletRequest request) { return tasks.get(user(request),id); }

    @PostMapping("/{id}/cancel")
    public Object cancel(@PathVariable String id,HttpServletRequest request) { return tasks.cancel(user(request),id); }
}
