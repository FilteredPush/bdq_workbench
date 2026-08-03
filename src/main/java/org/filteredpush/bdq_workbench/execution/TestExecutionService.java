/** TestExecutionService.java
 *
 * Service contract for executing a set of bound BDQ tests against a dataset of canonical records.
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
package org.filteredpush.bdq_workbench.execution;

import java.util.List;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;

/**
 * Executes a set of bound tests against a dataset of canonical records.
 *
 * <p>Implementations are responsible for invoking each {@link ImplementationBinding} (a
 * validation, amendment, measure, or issue test bound to a discovered implementation) against the
 * dataset's records and producing one {@link Response} per invocation, or, for multi-record
 * measures, a single aggregate response. The workbench's sole implementation,
 * {@link ParallelPhaseExecutionService}, orchestrates execution across the PRE_AMENDMENT,
 * AMENDMENT, and POST_AMENDMENT phases with deterministic response ordering, applying amendments
 * produced during the AMENDMENT phase to the dataset before POST_AMENDMENT tests are run against
 * it; implementations of this interface are otherwise free to choose their own execution and
 * ordering strategy.
 */
public interface TestExecutionService {

    /**
     * Executes the given bindings against the dataset's records.
     *
     * @param dataset the records to execute the bound tests against; implementations that amend
     *     records are expected to operate against a private copy so that the caller's dataset is
     *     not mutated as a side effect
     * @param bindings the resolved test-to-implementation bindings to execute, spanning whichever
     *     phases the implementation supports
     * @param discovered the full set of discovered implementations, used to resolve the
     *     reflective method and target instance metadata referenced by each binding
     * @return the responses produced by executing the bindings, one per test/record invocation,
     *     plus any synthesized multi-record measure responses
     */
    List<Response> execute(
            RecordDataset dataset,
            List<ImplementationBinding> bindings,
            List<DiscoveredImplementation> discovered);
}
