package org.filteredpush.bdq_workbench.model;

import java.util.Map;

/** Defines a policy test and execution hints. */
public record TestDefinition(String id, String label, TestType type, Phase phase, Map<String, String> parameters) {
}
