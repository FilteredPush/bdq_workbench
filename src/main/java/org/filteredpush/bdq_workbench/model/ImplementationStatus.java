/** ImplementationStatus.java
 *
 * Enumerates the discovery status of a policy test when mapped against discovered implementation methods.
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

/** Discovery status for policy tests mapped to implementation methods. */
public enum ImplementationStatus {
    /** Exactly one matching implementation method was discovered. */
    FOUND,
    /** No matching implementation method was discovered. */
    MISSING,
    /** More than one candidate implementation method was discovered. */
    AMBIGUOUS
}
