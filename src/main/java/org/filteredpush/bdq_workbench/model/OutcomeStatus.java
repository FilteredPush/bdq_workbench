/** OutcomeStatus.java
 *
 * Enumerates the high-level outcome classification for a single test execution.
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

/** Outcome classification for test execution. */
public enum OutcomeStatus {
    /** The test executed and its condition/criterion was satisfied. */
    PASSED,
    /** The test executed and its condition/criterion was not satisfied. */
    FAILED,
    /** The test executed and amended one or more term values. */
    AMENDED,
    /** No implementation was available to execute the test. */
    NOT_IMPLEMENTED,
    /** The test could not be run at all, e.g. because policy resolution or binding failed. */
    UNABLE_TO_RUN,
    /** The test's implementation threw an error during execution. */
    ERROR
}
