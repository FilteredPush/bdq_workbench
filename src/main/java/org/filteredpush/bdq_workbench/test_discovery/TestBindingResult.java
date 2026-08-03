/** TestBindingResult.java
 *
 * Aggregate outcome of binding a use case's resolved tests to discovered implementations.
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
package org.filteredpush.bdq_workbench.test_discovery;

import java.util.List;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.TestDefinition;

/**
 * Binding output with unresolved policy tests retained as first-class outcomes.
 *
 * <p>Returned by {@link TestBindingService#bind}, this record captures everything produced by
 * one binding pass over a use case's tests: the {@link ImplementationBinding}s that were
 * successfully matched to a discovered implementation (which may still be only partially bound,
 * see {@link org.filteredpush.bdq_workbench.model.BindingStatus}), the {@link TestDefinition}s
 * that could not be bound at all (including tests skipped because no implementation was
 * discovered, or a required built-in measure target could not be resolved), and a
 * {@link BindingReview} per attempted test suitable for a preflight report of what will and
 * will not run.
 *
 * @param bindings the tests that were matched to a discovered implementation method, whatever
 *     their resulting {@link org.filteredpush.bdq_workbench.model.BindingStatus}
 * @param unresolved the tests for which no usable implementation binding could be produced
 * @param reviews one diagnostic review per test that was considered during binding, describing
 *     the implementation status, binding status, parameterization capability, and any
 *     diagnostics that explain the outcome
 */
public record TestBindingResult(
        List<ImplementationBinding> bindings,
        List<TestDefinition> unresolved,
        List<BindingReview> reviews) {
}
