package org.filteredpush.bdq_workbench.test_discovery;

import java.util.List;

/** Discovers available test implementations from Java libraries. */
public interface TestDiscoveryService {
    List<DiscoveredImplementation> discover();
}
