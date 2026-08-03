/** PolicyResolverService.java
 *
 * Contract for resolving a BDQ use case identifier into an executable set of tests.
 *
 * Copyright 2026 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.filteredpush.bdq_workbench.rdf_policy;

import org.filteredpush.bdq_workbench.model.ExecutionPlan;

/**
 * Resolves a use case identifier into the policy and set of Data Quality tests that use case
 * requires, so that a {@link org.filteredpush.bdq_workbench.app.WorkbenchFacade} run can be
 * planned before any test is executed.
 *
 * <p>Implementations are expected to load use case and policy definitions from some backing
 * source (for example BDQ Use Cases / bdquc RDF or XML definitions, as in
 * {@link RdfPolicyResolverService}), match the requested use case against that source, and
 * follow the links from the matched use case's policy to the individual tests the policy
 * requires, distinguishing tests that could be identified and labeled from those that could not.
 *
 * <p>Callers such as {@link org.filteredpush.bdq_workbench.app.WorkbenchFacade#prepare} use the
 * returned {@link ExecutionPlan} both to drive test discovery/binding and to report, ahead of
 * execution, which of the policy's tests are resolvable.
 */
public interface PolicyResolverService {

    /**
     * Resolves the given use case identifier into an {@link ExecutionPlan}.
     *
     * @param useCaseId identifier (or URI) of the use case to resolve; implementations may fall
     *     back to a default use case when this is null, blank, or not found
     * @return the execution plan for the resolved use case, including its policy and the tests
     *     that were and were not successfully resolved from that policy
     */
    ExecutionPlan resolve(String useCaseId);
}
