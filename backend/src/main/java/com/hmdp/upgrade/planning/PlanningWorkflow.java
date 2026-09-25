package com.hmdp.upgrade.planning;

import java.time.LocalDateTime;
import java.util.*;

// Provider-independent read-only workflow. Booking is a separate explicit user command.
public final class PlanningWorkflow {
    public record Request(String city, LocalDateTime from, LocalDateTime until, int budgetCents, int people) {
        public Request {
            if (city == null || city.isBlank() || city.length()>60 || from == null || until == null || !from.isBefore(until)
                || budgetCents < 0 || people < 1 || people > 20) throw new IllegalArgumentException("Invalid constraints");
        }
    }
    public record Candidate(long id, String title, String description, String city, String area,
                            LocalDateTime startsAt, LocalDateTime endsAt, int priceCents, int available) {
        public Candidate(long id, String city, LocalDateTime startsAt, LocalDateTime endsAt,
                         int priceCents, int available) {
            this(id, "", "", city, "", startsAt, endsAt, priceCents, available);
        }
        public Candidate {
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            area = area == null ? "" : area;
            if (title.length() > 200 || description.length() > 2000 || area.length() > 100) {
                throw new IllegalArgumentException("Candidate text too large");
            }
        }
    }
    public record Plan(List<Long> sessions) {
        public Plan { sessions = List.copyOf(sessions); }
    }
    public record Result(String status, Plan plan, List<String> issues, List<String> advisories, int agentCalls) {
        public Result {
            issues = List.copyOf(issues);
            advisories = List.copyOf(advisories);
        }
        public Result(String status, Plan plan, List<String> issues, int agentCalls) {
            this(status, plan, issues, List.of(), agentCalls);
        }
    }
    public interface DiscoveryAgent { List<Long> search(Request request); }
    public interface PlannerAgent { Plan propose(Request request, List<Candidate> candidates, List<String> feedback); }
    public interface ReviewAgent { List<String> review(Request request, List<Candidate> candidates, Plan plan); }
    public interface Catalog { Candidate current(long id); }

    public static boolean hasFeasibleCandidate(Request request, List<Candidate> candidates) {
        Objects.requireNonNull(request);
        return candidates.stream().anyMatch(c -> c != null && c.id() > 0 && request.city().equals(c.city())
            && c.startsAt() != null && c.endsAt() != null && c.startsAt().isBefore(c.endsAt())
            && !c.startsAt().isBefore(request.from()) && !c.endsAt().isAfter(request.until())
            && c.priceCents() >= 0 && c.available() >= request.people()
            && (long) c.priceCents() * request.people() <= request.budgetCents());
    }

    public static Result noFeasibleResult(int visibleCount) {
        if (visibleCount >= 50) return new Result("NEEDS_REFINEMENT", null,
            List.of("Candidate search limit reached; cannot prove no match"), 0);
        return new Result("NO_MATCH", null, List.of("No feasible candidate under hard constraints"), 0);
    }
    private final DiscoveryAgent discovery;
    private final PlannerAgent planner;
    private final ReviewAgent reviewer;
    private final Catalog catalog;

    public PlanningWorkflow(DiscoveryAgent discovery, PlannerAgent planner, ReviewAgent reviewer, Catalog catalog) {
        this.discovery = discovery; this.planner = planner; this.reviewer = reviewer; this.catalog = catalog;
    }

    public Result run(Request request) {
        Objects.requireNonNull(request);
        int calls = 0;
        try {
            calls++;
            List<Long> ids = discovery.search(request);
            if (ids == null || ids.size() > 50 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
                return new Result("REJECTED", null, List.of("Invalid candidate set"), calls);
            }
            List<Candidate> candidates = ids.stream().distinct().map(catalog::current).filter(Objects::nonNull).toList();
            if (candidates.isEmpty()) return new Result("NO_MATCH", null, List.of("No candidates"), calls);
            List<String> feedback = List.of();
            List<String> advisories = new ArrayList<>();
            for (int round = 0; round < 2; round++) {
                calls++;
                Plan plan = planner.propose(request, candidates, feedback);
                calls++;
                List<String> review = reviewer.review(request, candidates, plan);
                if (review == null || review.size() > 20 || review.stream().anyMatch(Objects::isNull)) {
                    throw new IllegalArgumentException("Invalid review");
                }
                advisories.addAll(review);
                // Review prose is advisory. The single authoritative validation happens after review,
                // so two rounds of ten sessions fit the 20-read catalog budget.
                List<String> issues = validate(request, ids, plan);
                feedback = List.copyOf(new LinkedHashSet<>(issues));
                if (feedback.isEmpty()) return new Result("READY", plan, List.of(),
                    List.copyOf(new LinkedHashSet<>(advisories)), calls);
            }
            return new Result("NEEDS_REFINEMENT", null, feedback,
                List.copyOf(new LinkedHashSet<>(advisories)), calls);
        } catch (RuntimeException failure) {
            return new Result("FAILED", null, List.of("Agent or catalog failed; no booking performed"), calls);
        }
    }

    private List<String> validate(Request request, List<Long> allowed, Plan plan) {
        List<String> issues = new ArrayList<>();
        if (plan == null || plan.sessions().isEmpty() || plan.sessions().size() > 10) {
            issues.add("Plan requires 1..10 sessions"); return issues;
        }
        Set<Long> seen = new HashSet<>();
        List<Candidate> selected = new ArrayList<>();
        long cost = 0;
        for (long id : plan.sessions()) {
            if (!seen.add(id)) { issues.add("Duplicate session: " + id); continue; }
            if (!allowed.contains(id)) { issues.add("Unlisted session: " + id); continue; }
            Candidate c = catalog.current(id);
            if (c == null) { issues.add("Missing session: " + id); continue; }
            if (c.id() != id) { issues.add("Catalog identity mismatch: " + id); continue; }
            if (c.startsAt() == null || c.endsAt() == null || !c.startsAt().isBefore(c.endsAt()) || c.priceCents() < 0) {
                issues.add("Incomplete catalog: " + id); continue;
            }
            if (!request.city().equals(c.city())) issues.add("Wrong city: " + id);
            if (c.startsAt().isBefore(request.from()) || c.endsAt().isAfter(request.until())) issues.add("Outside time window: " + id);
            if (c.available() < request.people()) issues.add("Insufficient capacity: " + id);
            cost += (long)c.priceCents() * request.people();
            selected.add(c);
        }
        if (cost > request.budgetCents()) issues.add("Over total group budget");
        selected.sort(Comparator.comparing(Candidate::startsAt));
        for (int i = 1; i < selected.size(); i++) {
            if (selected.get(i).startsAt().isBefore(selected.get(i-1).endsAt())) issues.add("Overlapping sessions");
        }
        return issues;
    }
}
