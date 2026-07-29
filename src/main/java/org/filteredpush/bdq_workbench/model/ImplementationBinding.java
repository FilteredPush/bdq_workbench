package org.filteredpush.bdq_workbench.model;

import java.util.Map;

/** Binds a policy test to a discovered implementation method. */
public record ImplementationBinding(
        String testId,
        String implementationClass,
        String implementationMethod,
        Phase phase,
        Map<String, String> parameters) {
}
