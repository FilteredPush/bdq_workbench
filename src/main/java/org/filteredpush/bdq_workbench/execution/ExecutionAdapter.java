/** ExecutionAdapter.java
 *
 * Extension point abstracting how a single bound test implementation is invoked against a single canonical record.
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

import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;

/**
 * Adapter extension point for invoking a discovered test implementation method against a single
 * canonical record.
 *
 * <p>Implementations bridge the workbench's own model types ({@link CanonicalRecord},
 * {@link ImplementationBinding}) to whatever calling convention a bound test implementation
 * actually expects. The workbench's default implementation, {@link ReflectionExecutionAdapter},
 * invokes ffdq-compatible Java methods via reflection, but this interface allows an alternative
 * invocation strategy (for example, a remote service call) to be substituted without changing the
 * orchestration logic in {@link ParallelPhaseExecutionService}, which invokes this adapter once
 * per record/binding pair within each execution phase.
 */
public interface ExecutionAdapter {

    /**
     * Invokes the given implementation against a single record and produces its {@link Response}.
     *
     * <p>Implementations are expected not to throw for ordinary test failures or invocation
     * errors; such failures should instead be captured in the returned {@link Response} (typically
     * with an {@link org.filteredpush.bdq_workbench.model.OutcomeStatus#ERROR} outcome), so that
     * callers invoking this method many times in a loop, possibly concurrently, do not need to
     * handle exceptions per invocation.
     *
     * @param record the canonical record to test or amend
     * @param binding the resolved binding identifying which test, implementation method, phase,
     *     and parameters to use for this invocation
     * @param implementation the discovered implementation metadata (target instance and
     *     reflective method) to invoke; may be {@code null} if no matching implementation was
     *     discovered, in which case implementations should return an error response rather than
     *     fail
     * @return the response describing the outcome of invoking the test against the record
     */
    Response execute(CanonicalRecord record, ImplementationBinding binding, DiscoveredImplementation implementation);
}
