package org.filteredpush.bdq_workbench.execution;

import java.time.Instant;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;

/** Reflection-based adapter for ffdq-compatible implementation invocation. */
public class ReflectionExecutionAdapter implements ExecutionAdapter {

    @Override
    public Response execute(CanonicalRecord record, ImplementationBinding binding, DiscoveredImplementation implementation) {
        Instant start = Instant.now();
        try {
            Object result;
            if (implementation.method().getParameterCount() == 1) {
                result = implementation.method().invoke(implementation.target(), record.terms());
            } else if (implementation.method().getParameterCount() == 2) {
                result = implementation.method().invoke(implementation.target(), record.terms(), binding.parameters());
            } else {
                result = implementation.method().invoke(implementation.target());
            }
            OutcomeStatus status = Boolean.FALSE.equals(result) ? OutcomeStatus.FAILED : OutcomeStatus.PASSED;
            if (binding.phase() == org.filteredpush.bdq_workbench.model.Phase.AMENDMENT) {
                status = OutcomeStatus.AMENDED;
            }
            return new Response(
                    record.id(),
                    binding.testId(),
                    binding.implementationClass(),
                    binding.implementationMethod(),
                    binding.phase(),
                    binding.parameters(),
                    status,
                    result == null ? "null" : result.toString(),
                    start,
                    Instant.now());
        } catch (Exception e) {
            return new Response(
                    record.id(),
                    binding.testId(),
                    binding.implementationClass(),
                    binding.implementationMethod(),
                    binding.phase(),
                    binding.parameters(),
                    OutcomeStatus.ERROR,
                    e.getMessage(),
                    start,
                    Instant.now());
        }
    }
}
