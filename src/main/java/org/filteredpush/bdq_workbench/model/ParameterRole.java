/** ParameterRole.java
 *
 * Enumerates the source role that a reflected test method parameter plays in a BDQ test's signature.
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

/** Source role for a reflected test method parameter. */
public enum ParameterRole {
    /** Parameter bound to the Darwin Core term(s) the test acts upon (may be amended). */
    ACTED_UPON,
    /** Parameter bound to a Darwin Core term the test consults but does not amend. */
    CONSULTED,
    /** Parameter bound to an explicit, non-term configuration value. */
    PARAMETER,
    /** Legacy-style parameter bound to the entire record. */
    LEGACY_RECORD,
    /** Legacy-style parameter bound to the entire parameters map. */
    LEGACY_PARAMETERS
}
