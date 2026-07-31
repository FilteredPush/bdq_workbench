package org.filteredpush.bdq_workbench.execution;

import java.time.Instant;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reflection-based adapter for ffdq-compatible implementation invocation. */
public class ReflectionExecutionAdapter implements ExecutionAdapter {
	
	private static final Logger LOG = LoggerFactory.getLogger(ReflectionExecutionAdapter.class);

    @Override
    public Response execute(CanonicalRecord record, ImplementationBinding binding, DiscoveredImplementation implementation) {
        Instant start = Instant.now();
        try {
            Object result;
            if (implementation.method().getParameterCount() == 1) {
            	LOG.debug("Executing {}.{} for record {} with paramcount=1 terms {}", binding.implementationClass(), binding.implementationMethod(), record.id(), record.terms());
            	result = implementation.method().invoke(implementation.target(), record.terms());
            } else if (implementation.method().getParameterCount() == 2) {
            	LOG.debug("Executing {}.{} for record {} with paramcount=2 terms {} and parameters {}", binding.implementationClass(), binding.implementationMethod(), record.id(), record.terms(), binding.parameters());
                result = implementation.method().invoke(implementation.target(), record.terms(), binding.parameters());
            } else {
            	LOG.debug("Executing {}.{} for record {} with paramcount=0", binding.implementationClass(), binding.implementationMethod(), record.id());
                result = implementation.method().invoke(implementation.target());
            }
            OutcomeStatus status = Boolean.FALSE.equals(result) ? OutcomeStatus.FAILED : OutcomeStatus.PASSED;
            if (binding.phase() == org.filteredpush.bdq_workbench.model.Phase.AMENDMENT) {
                status = OutcomeStatus.AMENDED;
            }
            LOG.debug("Executed {}.{} for record {} with status {}", binding.implementationClass(), binding.implementationMethod(), record.id(), status);
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
        	LOG.error("Error executing {}.{} for record {}: {}", binding.implementationClass(), binding.implementationMethod(), record.id(), e.getMessage(), e);
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
