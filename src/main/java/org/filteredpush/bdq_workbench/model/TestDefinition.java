package org.filteredpush.bdq_workbench.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Defines a policy test and execution hints. */
public record TestDefinition(
        String id,
        String label,
        TestType type,
        Phase phase,
        Map<String, String> parameters,
        Map<String, String> metadata) {

    public TestDefinition {
        parameters = Map.copyOf(parameters == null ? Map.of() : new LinkedHashMap<>(parameters));
        metadata = Map.copyOf(metadata == null ? Map.of() : new LinkedHashMap<>(metadata));
    }

    public TestDefinition(String id, String label, TestType type, Phase phase, Map<String, String> parameters) {
        this(id, label, type, phase, parameters, Map.of());
    }
}
