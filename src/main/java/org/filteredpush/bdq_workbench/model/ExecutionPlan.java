/** ExecutionPlan.java
 *
 * Resolved policy plan for a selected use case, listing the tests to run and any that could not be resolved.
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
package org.filteredpush.bdq_workbench.model;

import java.util.List;

/**
 * Resolved policy plan for a selected use case.
 *
 * @param useCase the use case this plan was resolved for
 * @param policy the policy linked to the use case, listing the test IDs it references
 * @param tests the policy's test definitions that were successfully resolved and are eligible to run
 * @param unresolvedTests the policy's test definitions that could not be resolved (e.g. unknown
 *     test ID) and will be synthesized as {@code UNABLE_TO_RUN} in the final execution summary
 */
public record ExecutionPlan(
        UseCase useCase,
        Policy policy,
        List<TestDefinition> tests,
        List<TestDefinition> unresolvedTests) {
}
