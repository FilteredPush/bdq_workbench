package org.filteredpush.bdq_workbench.model;

import java.time.Instant;
import java.util.Map;

/** Result for a single test execution with provenance details. */
public record Response(
        String recordId,
        String testId,
        TestType testType,
        String implementationClass,
        String implementationMethod,
        Phase phase,
        Map<String, String> parameters,
        OutcomeStatus status,
        String responseStatus,
        String responseResult,
        String comment,
        String message,
        Map<String, String> amendments,
        Instant startedAt,
        Instant finishedAt) {
}
