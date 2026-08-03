package org.filteredpush.bdq_workbench.model;

import java.util.List;
import org.filteredpush.bdq_workbench.app.AppConfig;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingResult;

/** Prepared execution state reused between preflight review and execution. */
public record PreparedRun(
        AppConfig config,
        RecordDataset dataset,
        ExecutionPlan plan,
        List<DiscoveredImplementation> discovered,
        TestBindingResult bindingResult) {
}
