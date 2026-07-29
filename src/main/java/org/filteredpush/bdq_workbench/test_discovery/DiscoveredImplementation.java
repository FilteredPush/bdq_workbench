package org.filteredpush.bdq_workbench.test_discovery;

import java.lang.reflect.Method;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.Phase;

/** Discovered implementation metadata with invocation handle. */
public record DiscoveredImplementation(
        String providedTestId,
        Phase phase,
        String implementationClass,
        String implementationMethod,
        Map<String, String> parameters,
        Object target,
        Method method) {
}
