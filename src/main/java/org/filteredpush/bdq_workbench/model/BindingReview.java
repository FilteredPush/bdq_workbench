/** BindingReview.java
 *
 * User-facing binding review row summarizing how a single test was (or was not) bound to an implementation, for preflight diagnostics.
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

import java.util.List;
import java.util.Map;

/**
 * User-facing binding review row for preflight diagnostics.
 *
 * @param test the policy test this review describes
 * @param implementationStatus whether a matching implementation was found, missing, or ambiguous
 * @param bindingStatus completeness of the binding to a candidate implementation method
 * @param parameterizationCapability whether the test exposes default and/or parameterized methods
 * @param chosenImplementationMethod the implementation method selected for this test, if any
 * @param parameterValues the parameter values that would be (or were) supplied to the method
 * @param usingDefaultParameters whether the chosen method is being invoked with default parameters
 *     rather than explicit values
 * @param diagnostics human-readable diagnostic messages explaining the binding outcome
 */
public record BindingReview(
        TestDefinition test,
        ImplementationStatus implementationStatus,
        BindingStatus bindingStatus,
        ParameterizationCapability parameterizationCapability,
        String chosenImplementationMethod,
        Map<String, String> parameterValues,
        boolean usingDefaultParameters,
        List<String> diagnostics) {
}
