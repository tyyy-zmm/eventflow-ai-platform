package com.hmdp.upgrade.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class PlanningEngine {
    public record Input(PlanningWorkflow.Request constraints,String preference) {
        public Input {
            if(constraints==null || (preference!=null && preference.length()>2000)) throw new IllegalArgumentException("Structured constraints required");
            preference=preference==null ? "" : preference;
        }
    }
    public record Output(PlanningWorkflow.Result result,int modelCalls,Integer totalTokens,String mode,List<PlanningWorkflow.Candidate> selected) {}
    private final JdbcTemplate db;
    private final ModelClient model;
    private final ObjectMapper json;
    private final String mode;
    public PlanningEngine(JdbcTemplate db,ModelClient model,ObjectMapper json) {
        this.db=new JdbcTemplate(java.util.Objects.requireNonNull(db.getDataSource())); this.db.setQueryTimeout(2); this.model=model;this.json=json;
        this.mode=model.mode();
    }
    public boolean enabled() { return model.enabled(); }
    public Output execute(Input input) {
        return execute(input,60000);
    }
    public Output execute(Input input,long remainingMillis) {
        var budget=new ModelClient.Budget(remainingMillis);
        var request=input.constraints();
        List<PlanningWorkflow.Candidate> available=db.query("SELECT e.id,e.title,CONCAT(s.name,'：',s.description,'；标签：',e.tags),p.city,p.area,e.starts_at,e.ends_at,e.price_cents,e.available "
            + "FROM ux_experience_session e JOIN ux_shop s ON s.id=e.shop_id JOIN ux_storefront p ON p.shop_id=s.id "
            + "WHERE e.published=TRUE AND p.published=TRUE AND p.city=? AND e.starts_at>=? AND e.starts_at>CURRENT_TIMESTAMP AND e.ends_at<=? ORDER BY e.starts_at,e.id LIMIT 50",
            (rs,n)->new PlanningWorkflow.Candidate(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getTimestamp(6).toLocalDateTime(),rs.getTimestamp(7).toLocalDateTime(),rs.getInt(8),rs.getInt(9)),
            request.city(),java.sql.Timestamp.valueOf(request.from()),java.sql.Timestamp.valueOf(request.until()));
        if (!PlanningWorkflow.hasFeasibleCandidate(request, available)) {
            return new Output(PlanningWorkflow.noFeasibleResult(available.size()), 0, 0, mode, List.of());
        }
        Map<Long,PlanningWorkflow.Candidate> initial=new java.util.HashMap<>();
        available.forEach(c->initial.put(c.id(),c));
        java.util.Set<Long> visited=new java.util.HashSet<>();
        var toolReads=new java.util.concurrent.atomic.AtomicInteger();
        var latestFeedback=new java.util.concurrent.atomic.AtomicReference<List<String>>(List.of());
        var workflow=new PlanningWorkflow(r -> {
                List<Long> found=PlanningModelOutput.ids(model.complete("discovery",Map.of("constraints",r,"preference",input.preference(),"candidates",available),budget),"ids",50);
                if(!initial.keySet().containsAll(found)) throw new IllegalArgumentException("Discovery invented IDs");
                if(!PlanningWorkflow.hasFeasibleCandidate(r,found.stream().map(initial::get).toList()))
                    throw new IllegalArgumentException("Discovery removed all feasible candidates");
                return found;
            },
            (r,c,f) -> {
                latestFeedback.set(f);
                return new PlanningWorkflow.Plan(PlanningModelOutput.ids(model.complete("planner",Map.of("constraints",r,"preference",input.preference(),"candidates",c,"feedback",f),budget),"sessions",10));
            }, (r,c,p) -> {
                List<String> reasons=PlanningReviewPolicy.reasons(input.preference(),c,p,latestFeedback.get());
                if(reasons.isEmpty()) return List.of();
                try {
                    return PlanningModelOutput.reviewIssues(model.complete("review",Map.of("constraints",r,"preference",input.preference(),"candidates",c,"plan",p,"reviewReasons",reasons),budget));
                } catch(RuntimeException unavailable) {
                    return List.of("Review unavailable; plan accepted only by deterministic hard validation");
                }
            },id -> {
                if(visited.add(id)) return initial.get(id);
                if(toolReads.incrementAndGet()>20) throw new IllegalStateException("Read tool budget exceeded");
                return current(id);
            });
        var result=workflow.run(request);
        List<PlanningWorkflow.Candidate> selected=result.plan()==null ? List.of() : result.plan().sessions().stream()
            .map(this::current).filter(java.util.Objects::nonNull).toList();
        return new Output(result,budget.calls(),budget.knownTokens(),mode,selected);
    }
    private PlanningWorkflow.Candidate current(long id) {
        var rows=db.query("SELECT e.id,e.title,CONCAT(s.name,'：',s.description,'；标签：',e.tags),p.city,p.area,e.starts_at,e.ends_at,e.price_cents,e.available "
            + "FROM ux_experience_session e JOIN ux_shop s ON s.id=e.shop_id JOIN ux_storefront p ON p.shop_id=s.id "
            + "WHERE e.id=? AND e.published=TRUE AND p.published=TRUE AND e.starts_at>CURRENT_TIMESTAMP",
            (rs,n)->new PlanningWorkflow.Candidate(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getTimestamp(6).toLocalDateTime(),rs.getTimestamp(7).toLocalDateTime(),rs.getInt(8),rs.getInt(9)),id);
        return rows.isEmpty()?null:rows.get(0);
    }
}
