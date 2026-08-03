/** TestDefinition.java
 *
 * Defines a single BDQ policy test together with its execution hints and metadata.
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines a policy test and execution hints.
 *
 * @param id the test's identifier
 * @param label the test's label (e.g. a BDQ test name such as
 *     {@code VALIDATION_COUNTRY_NOTEMPTY}), used among other things to recognize built-in
 *     multi-record measures (see {@link BuiltInMeasureSpec#from(TestDefinition)})
 * @param type the test's high-level category
 * @param phase the execution phase this test runs in
 * @param parameters explicit configuration parameter values for this test
 * @param metadata additional descriptive metadata for this test, such as
 *     {@link BuiltInMeasureSpec#EXPECTED_RESPONSE_METADATA_KEY}
 */
public record TestDefinition(
        String id,
        String label,
        TestType type,
        Phase phase,
        Map<String, String> parameters,
        Map<String, String> metadata) {

    /**
     * Canonical constructor; defensively copies {@code parameters} and {@code metadata},
     * substituting empty maps for null arguments.
     */
    public TestDefinition {
        parameters = Map.copyOf(parameters == null ? Map.of() : new LinkedHashMap<>(parameters));
        metadata = Map.copyOf(metadata == null ? Map.of() : new LinkedHashMap<>(metadata));
    }

    /**
     * Creates a test definition with no additional metadata.
     *
     * @param id the test's identifier
     * @param label the test's label
     * @param type the test's high-level category
     * @param phase the execution phase this test runs in
     * @param parameters explicit configuration parameter values for this test
     */
    public TestDefinition(String id, String label, TestType type, Phase phase, Map<String, String> parameters) {
        this(id, label, type, phase, parameters, Map.of());
    }
}
