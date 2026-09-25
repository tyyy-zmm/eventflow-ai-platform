package com.hmdp.upgrade.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class PlanningReviewPolicy {
    private static final Pattern INJECTION = Pattern.compile(
        "(ignore|bypass|override).{0,30}(rule|instruction|system|policy)|"
        + "(book|pay|payment).{0,30}(immediately|succeeded|success)|"
        + "忽略.{0,20}(规则|指令|系统)|绕过.{0,20}(规则|校验)|立即.{0,20}(预订|付款)|声称.{0,20}(支付|付款).{0,10}成功");

    private PlanningReviewPolicy() {}

    static List<String> reasons(String preference, List<PlanningWorkflow.Candidate> candidates,
                                PlanningWorkflow.Plan plan, List<String> feedback) {
        List<String> reasons = new ArrayList<>();
        String text = preference == null ? "" : preference.toLowerCase(Locale.ROOT);
        if (INJECTION.matcher(text).find()) reasons.add("SUSPICIOUS_PREFERENCE");
        if (plan != null && plan.sessions().size() > 1) reasons.add("MULTI_SESSION_PLAN");
        if (candidates.size() >= 40) reasons.add("CANDIDATE_WINDOW_PRESSURE");
        if (feedback != null && !feedback.isEmpty()) reasons.add("VALIDATION_REVISION");
        return List.copyOf(reasons);
    }
}
