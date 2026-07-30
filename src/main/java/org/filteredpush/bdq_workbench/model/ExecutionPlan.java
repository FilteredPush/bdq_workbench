package org.filteredpush.bdq_workbench.model;

import java.util.List;

/** Resolved policy plan for a selected use case. */
public record ExecutionPlan(
        UseCase useCase,
        Policy policy,
        List<TestDefinition> tests,
        List<TestDefinition> unresolvedTests) {
}
