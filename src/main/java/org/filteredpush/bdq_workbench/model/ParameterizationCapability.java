/** ParameterizationCapability.java
 *
 * Enumerates whether a test exposes default and/or parameterized implementation methods.
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

/** Whether a test exposes default and/or parameterized implementation methods. */
public enum ParameterizationCapability {
    /** Only a default (non-parameterized) implementation method is available. */
    DEFAULT_ONLY,
    /** Only a parameterized implementation method is available. */
    PARAMETERIZED_ONLY,
    /** Both a default and a parameterized implementation method are available. */
    BOTH
}
