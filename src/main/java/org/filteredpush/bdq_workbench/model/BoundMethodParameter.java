/** BoundMethodParameter.java
 *
 * Records the binding decision made for a single reflected implementation method parameter.
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

/**
 * Binding decision for a specific reflected method parameter.
 *
 * @param parameter the reflected method parameter being bound
 * @param resolvedSource description of where the supplied value came from (e.g. a Darwin Core
 *     term name or a default), if resolved
 * @param suppliedValue the value that would be (or was) supplied for this parameter
 * @param bound whether this parameter was successfully bound
 * @param reason human-readable explanation of the binding outcome, especially when not bound
 */
public record BoundMethodParameter(
        MethodParameter parameter,
        String resolvedSource,
        String suppliedValue,
        boolean bound,
        String reason) {
}
