package org.filteredpush.bdq_workbench.model;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Summary of execution outputs including unresolved outcomes. */
public record ExecutionSummary(List<Response> responses) {

    public List<Response> responsesForPhase(Phase phase) {
        return filter(response -> response.phase() == phase);
    }

    public List<Response> responsesForType(TestType type) {
        return filter(response -> response.testType() == type);
    }

    public List<Response> responsesForResponseStatus(String responseStatus) {
        return filter(response -> responseStatus.equals(response.responseStatus()));
    }

    public long countByPhaseAndStatus(Phase phase, String responseStatus) {
        return responses.stream()
                .filter(response -> response.phase() == phase)
                .filter(response -> responseStatus.equals(response.responseStatus()))
                .count();
    }

    public long countByTypeAndResult(TestType type, String responseResult) {
        return responses.stream()
                .filter(response -> response.testType() == type)
                .filter(response -> responseResult.equals(response.responseResult()))
                .count();
    }

    public Map<String, Long> countsByResponseStatus() {
        return responses.stream()
                .collect(Collectors.groupingBy(response -> defaulted(response.responseStatus()), Collectors.counting()));
    }

    public Map<String, Long> countsByResponseResult() {
        return responses.stream()
                .collect(Collectors.groupingBy(response -> defaulted(response.responseResult()), Collectors.counting()));
    }

    public Map<Phase, Long> countsByPhase() {
        return responses.stream()
                .collect(Collectors.groupingBy(Response::phase, Collectors.counting()));
    }

    private List<Response> filter(Predicate<Response> predicate) {
        return responses.stream().filter(predicate).toList();
    }

    private static String defaulted(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
