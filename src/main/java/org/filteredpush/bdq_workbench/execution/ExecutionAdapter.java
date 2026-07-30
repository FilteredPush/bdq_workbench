package org.filteredpush.bdq_workbench.execution;

import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;

/** Adapter extension point for invoking discovered test methods. */
public interface ExecutionAdapter {
    Response execute(CanonicalRecord record, ImplementationBinding binding, DiscoveredImplementation implementation);
}
