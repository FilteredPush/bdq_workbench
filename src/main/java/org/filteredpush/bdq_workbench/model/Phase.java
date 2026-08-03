/** Phase.java
 *
 * Enumerates the execution phase for BDQ tests, determining the order in which validations and amendments run relative to each other.
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

/** Execution phase for BDQ tests. */
public enum Phase {
    /** Runs before any amendments are applied. */
    PRE_AMENDMENT,
    /** Applies amendments to term values. */
    AMENDMENT,
    /** Runs after amendments have been applied. */
    POST_AMENDMENT
}
