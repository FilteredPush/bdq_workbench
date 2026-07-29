package org.filteredpush.bdq_workbench.rdf_policy;

import org.filteredpush.bdq_workbench.model.ExecutionPlan;

/** Resolves use case and policy relationships into executable tests. */
public interface PolicyResolverService {
    ExecutionPlan resolve(String useCaseId);
}
