package com.hmdp.upgrade.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

final class PlanningModelOutput {
    private PlanningModelOutput() {}

    static List<Long> ids(JsonNode reply, String field, int limit) {
        if (reply == null || !reply.isObject() || reply.size() != 1 || !reply.path(field).isArray()
            || reply.path(field).size() > limit) throw new IllegalArgumentException("Invalid IDs");
        List<Long> ids = new ArrayList<>();
        for (JsonNode id : reply.get(field)) {
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0) {
                throw new IllegalArgumentException("Invalid ID");
            }
            ids.add(id.asLong());
        }
        return ids;
    }

    static List<String> reviewIssues(JsonNode reply) {
        if (reply == null || !reply.isObject() || reply.size() != 1 || !reply.path("issues").isArray()
            || reply.path("issues").size() > 20) throw new IllegalArgumentException("Invalid review");
        List<String> issues = new ArrayList<>();
        for (JsonNode issue : reply.get("issues")) {
            if (!issue.isTextual() || issue.asText().length() > 500) throw new IllegalArgumentException("Invalid issue");
            issues.add(issue.asText());
        }
        return issues;
    }
}
