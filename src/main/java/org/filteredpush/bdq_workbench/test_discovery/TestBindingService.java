package org.filteredpush.bdq_workbench.test_discovery;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.TestDefinition;

/** Resolves policy tests to discovered implementations. */
public interface TestBindingService {
    TestBindingResult bind(
            List<TestDefinition> tests,
            List<DiscoveredImplementation> discovered,
            Map<String, String> explicitMapping);
}
