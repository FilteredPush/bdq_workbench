/** DiscoveredImplementation.java
 *
 * Metadata and invocation handle for a single test implementation method found on the classpath.
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
package org.filteredpush.bdq_workbench.test_discovery;

import java.lang.reflect.Method;
import java.util.List;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * Discovered implementation metadata with invocation handle.
 *
 * <p>Produced by a {@link TestDiscoveryService} for each annotated method it finds (identified
 * by an ffdq-style {@code @Provides} annotation), this record carries everything a
 * {@link TestBindingService} needs to decide whether the method is a suitable candidate for a
 * given {@link org.filteredpush.bdq_workbench.model.TestDefinition}, and everything the
 * execution service later needs to actually invoke it via reflection.
 *
 * @param providedTestId the test identifier read from the method's {@code @Provides} annotation,
 *     or {@code null} if absent
 * @param providedVersion the version-qualified identifier read from the method's
 *     {@code @ProvidesVersion} annotation, or {@code null} if absent
 * @param testType the {@link TestType} inferred from the method's annotations ({@code UNKNOWN}
 *     if none of the recognized type annotations are present)
 * @param phase the {@link Phase} inferred from {@code testType} ({@link Phase#AMENDMENT} for
 *     amendment tests, {@link Phase#PRE_AMENDMENT} otherwise)
 * @param implementationClass the fully qualified name of the class declaring the method
 * @param implementationMethod the name of the discovered method
 * @param specification the human-readable specification text read from the method's
 *     {@code @Specification} annotation, or {@code null} if absent
 * @param parameters metadata for each of the method's reflected parameters, describing its role
 *     (acted-upon term, consulted term, user parameter, or legacy positional argument)
 * @param target the instantiated object the method should be invoked on, or {@code null} if the
 *     method is static
 * @param method the reflective handle used to invoke this implementation
 */
public record DiscoveredImplementation(
        String providedTestId,
        String providedVersion,
        TestType testType,
        Phase phase,
        String implementationClass,
        String implementationMethod,
        String specification,
        List<MethodParameter> parameters,
        Object target,
        Method method) {

    /**
     * Reports whether this implementation exposes at least one user-supplied parameter (as
     * opposed to only acted-upon/consulted term arguments), i.e. whether it is a parameterized
     * variant rather than a default-only implementation.
     *
     * @return {@code true} if any of {@link #parameters()} has role
     *     {@link org.filteredpush.bdq_workbench.model.ParameterRole#PARAMETER}
     */
    public boolean isParameterized() {
        return parameters.stream().anyMatch(parameter -> parameter.role() == org.filteredpush.bdq_workbench.model.ParameterRole.PARAMETER);
    }
}
