package org.filteredpush.bdq_workbench.execution;

import java.util.List;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;

/** Executes bound tests against canonical records. */
public interface TestExecutionService {
    List<Response> execute(
            RecordDataset dataset,
            List<ImplementationBinding> bindings,
            List<DiscoveredImplementation> discovered);
}
