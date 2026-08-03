/** Policy.java
 *
 * Represents a policy as a named collection of linked test identifiers to be executed for a use case.
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
 * Policy containing linked test identifiers.
 *
 * @param id the policy's identifier
 * @param testIds the identifiers of the tests this policy links to
 */
public record Policy(String id, List<String> testIds) {
}
