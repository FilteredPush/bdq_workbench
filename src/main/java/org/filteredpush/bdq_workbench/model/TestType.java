/** TestType.java
 *
 * Enumerates the high-level BDQ test category a test belongs to.
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

/** High-level BDQ test category. */
public enum TestType {
    /** Checks whether a record's data complies with a criterion. */
    VALIDATION,
    /** Flags a potential data quality issue without a strict compliance verdict. */
    ISSUE,
    /** Computes a summary metric, often across multiple records. */
    MEASURE,
    /** Modifies one or more term values to improve data quality. */
    AMENDMENT,
    /** The test's category could not be determined. */
    UNKNOWN
}
