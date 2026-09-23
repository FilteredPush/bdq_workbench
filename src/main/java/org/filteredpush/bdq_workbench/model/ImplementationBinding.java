/** ImplementationBinding.java
 *
 * Binds a policy test to a discovered implementation method, capturing how each of the method's parameters was resolved.
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

import java.util.Map;

/**
 * Binds a policy test to a discovered implementation method.
 *
 * @param testId the identifier of the test being bound
 * @param testType the high-level category of the test being bound
 * @param implementationClass the fully-qualified class name of the bound implementation
 * @param implementationMethod the name of the bound implementation method
 * @param phase the execution phase of the test being bound
 * @param parameters the parameter values to supply when invoking the implementation method
 * @param bindingStatus how completely this test was bound to the implementation method
 * @param parameterizationCapability whether the test exposes default and/or parameterized methods
 * @param methodSelection description of which candidate method was selected and why
 * @param usingDefaultParameters whether the bound method is being invoked with default parameters
 *     rather than explicit values
 * @param parameterBindings the binding decision made for each reflected method parameter
 * @param diagnostics human-readable diagnostic messages explaining the binding outcome
 */
public record ImplementationBinding(
        String testId,
        TestType testType,
        String implementationClass,
        String implementationMethod,
        Phase phase,
        Map<String, String> parameters,
        BindingStatus bindingStatus,
        ParameterizationCapability parameterizationCapability,
        String methodSelection,
        boolean usingDefaultParameters,
        java.util.List<BoundMethodParameter> parameterBindings,
        java.util.List<String> diagnostics) {

    /**
     * Reports whether this binding is executable, as opposed to being retained only for
     * diagnostics and synthesized unresolved reporting.
     *
     * @return {@code true} if {@link #bindingStatus()} is runnable
     */
    public boolean isRunnable() {
        return bindingStatus != null && bindingStatus.isRunnable();
    }

    /**
     * Builds the legacy implementation lookup key used by older explicit mappings.
     *
     * @return {@code "<implementationClass>#<implementationMethod>"}
     */
    public String legacyImplementationKey() {
        return implementationClass + "#" + implementationMethod;
    }

    /**
     * Builds a stable full implementation signature from this binding's ordered bound parameters.
     *
     * <p>The signature uses only Java parameter types because those are stable across discovery,
     * explicit mappings, and runtime reflective lookup. The richer role/source metadata remains on
     * {@link #parameterBindings()} and is checked separately when resolving a discovered
     * implementation at execution time. When no bound-parameter metadata is present, this falls
     * back to the legacy {@code class#method} form rather than guessing an overload signature.
     *
     * @return {@code "<implementationClass>#<implementationMethod>(type1, type2, ...)"} when
     *     bound-parameter metadata is available, otherwise the legacy
     *     {@code "<implementationClass>#<implementationMethod>"} form
     */
    public String fullImplementationSignature() {
        return parameterBindings == null || parameterBindings.isEmpty()
                ? legacyImplementationKey()
                : legacyImplementationKey() + parameterTypeSignature();
    }

    /**
     * Renders the bound method's ordered Java parameter-type signature.
     *
     * @return {@code "(type1, type2, ...)"} or {@code "()"} when no parameters are bound
     */
    public String parameterTypeSignature() {
        return parameterBindings == null
                ? "()"
                : parameterBindings.stream()
                        .map(BoundMethodParameter::parameter)
                        .sorted(java.util.Comparator.comparingInt(MethodParameter::index))
                        .map(MethodParameter::typeName)
                        .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }
}
