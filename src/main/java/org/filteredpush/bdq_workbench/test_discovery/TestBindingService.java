/** TestBindingService.java
 *
 * Contract for resolving a use case's policy tests to discovered implementation methods.
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.TestDefinition;

/**
 * Resolves policy tests to discovered implementations.
 *
 * <p>Given the {@link org.filteredpush.bdq_workbench.model.TestDefinition}s that make up a
 * resolved use case and the {@link DiscoveredImplementation}s located by a
 * {@link TestDiscoveryService}, a binding service chooses, for each test, which implementation
 * method (if any) it will be executed with, and how that method's parameters map to record
 * terms or supplied values. The outcome — bound implementations, tests that could not be
 * resolved, and per-test diagnostic reviews — is returned as a {@link TestBindingResult}.
 */
public interface TestBindingService {

    /**
     * Binds each policy test to a discovered implementation, without regard to which Darwin
     * Core terms are actually present in the dataset being processed.
     *
     * @param tests the resolved tests from a use case's policy
     * @param discovered the implementations located by a {@link TestDiscoveryService}
     * @param explicitMapping test ID to {@code implementationClass#implementationMethod} overrides
     *     that take precedence over automatic candidate selection
     * @return the bindings, unresolved tests, and diagnostic reviews produced by this attempt
     */
    TestBindingResult bind(
            List<TestDefinition> tests,
            List<DiscoveredImplementation> discovered,
            Map<String, String> explicitMapping);

    /**
     * Binds each policy test to a discovered implementation, taking into account which terms
     * are actually available in the dataset so that acted-upon/consulted parameters can be
     * checked for presence.
     *
     * <p>The default implementation delegates to {@link #bind(List, List, Map)}, ignoring
     * {@code availableTerms}; implementations that can evaluate term availability should
     * override this method.
     *
     * @param tests the resolved tests from a use case's policy
     * @param discovered the implementations located by a {@link TestDiscoveryService}
     * @param explicitMapping test ID to {@code implementationClass#implementationMethod} overrides
     *     that take precedence over automatic candidate selection
     * @param availableTerms the Darwin Core term names present in the dataset being processed
     * @return the bindings, unresolved tests, and diagnostic reviews produced by this attempt
     */
    default TestBindingResult bind(
            List<TestDefinition> tests,
            List<DiscoveredImplementation> discovered,
            Map<String, String> explicitMapping,
            Collection<String> availableTerms) {
        return bind(tests, discovered, explicitMapping);
    }
}
