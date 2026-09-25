package com.hmdp.upgrade.planning;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanningWorkflowTest {
    private final LocalDateTime start=LocalDateTime.of(2030,1,2,9,0);
    private final PlanningWorkflow.Request request=new PlanningWorkflow.Request("上海",start,start.plusHours(12),20_000,2);

    @Test void acceptsPlanOnlyAfterDeterministicValidation() {
        var first=candidate(1,start.plusHours(1),start.plusHours(2),3_000,4);
        var second=candidate(2,start.plusHours(3),start.plusHours(4),4_000,2);
        var catalog=Map.of(1L,first,2L,second);
        var workflow=new PlanningWorkflow(r->List.of(1L,2L),(r,c,f)->new PlanningWorkflow.Plan(List.of(1L,2L)),
            (r,c,p)->List.of(),catalog::get);

        var result=workflow.run(request);

        assertEquals("READY",result.status());
        assertEquals(List.of(1L,2L),result.plan().sessions());
        assertTrue(result.issues().isEmpty());
    }

    @Test void rejectsAgentPlanThatExceedsHardBudget() {
        var costly=candidate(1,start.plusHours(1),start.plusHours(2),11_000,4);
        var workflow=new PlanningWorkflow(r->List.of(1L),(r,c,f)->new PlanningWorkflow.Plan(List.of(1L)),
            (r,c,p)->List.of(),id->costly);

        var result=workflow.run(request);

        assertEquals("NEEDS_REFINEMENT",result.status());
        assertTrue(result.issues().contains("Over total group budget"));
    }

    @Test void rejectsOverlappingSessionsEvenWhenReviewerApproves() {
        var first=candidate(1,start.plusHours(1),start.plusHours(3),2_000,4);
        var second=candidate(2,start.plusHours(2),start.plusHours(4),2_000,4);
        var catalog=Map.of(1L,first,2L,second);
        var workflow=new PlanningWorkflow(r->List.of(1L,2L),(r,c,f)->new PlanningWorkflow.Plan(List.of(1L,2L)),
            (r,c,p)->List.of(),catalog::get);

        var result=workflow.run(request);

        assertEquals("NEEDS_REFINEMENT",result.status());
        assertTrue(result.issues().contains("Overlapping sessions"));
    }

    private PlanningWorkflow.Candidate candidate(long id,LocalDateTime from,LocalDateTime until,int price,int available) {
        return new PlanningWorkflow.Candidate(id,"体验 "+id,"说明","上海","徐汇",from,until,price,available);
    }
}
