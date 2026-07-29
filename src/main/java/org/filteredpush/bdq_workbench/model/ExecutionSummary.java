package org.filteredpush.bdq_workbench.model;

import java.util.List;

/** Summary of execution outputs including unresolved outcomes. */
public record ExecutionSummary(List<Response> responses) {
}
