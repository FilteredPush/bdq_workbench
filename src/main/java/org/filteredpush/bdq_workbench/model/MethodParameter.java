/** MethodParameter.java
 *
 * Reflected method parameter metadata describing a single parameter of a discovered implementation method, used for execution binding.
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
 * Reflected method parameter metadata used for execution binding.
 *
 * @param index the zero-based position of this parameter in the method's parameter list
 * @param name the parameter's declared name
 * @param role the source role this parameter plays (e.g. acted-upon term, consulted term, or
 *     explicit parameter)
 * @param source the Darwin Core term name or other source identifier this parameter is bound to,
 *     if applicable
 * @param typeName the fully-qualified name of the parameter's declared type
 * @param required whether a value must be resolved for this parameter for the method to be bound
 */
public record MethodParameter(
        int index,
        String name,
        ParameterRole role,
        String source,
        String typeName,
        boolean required) {
}
