/** TestDiscoveryService.java
 *
 * Contract for locating BDQ test implementation methods available on the classpath.
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

import java.util.List;

/**
 * Discovers available test implementations from Java libraries.
 *
 * <p>An implementation is expected to locate methods annotated per the ffdq-style test
 * annotation conventions (e.g. {@code @Provides}, {@code @Validation}, {@code @Amendment},
 * {@code @Measure}, {@code @Issue}) and describe each as a {@link DiscoveredImplementation}
 * that a {@link TestBindingService} can later match against a use case's resolved
 * {@link org.filteredpush.bdq_workbench.model.TestDefinition}s.
 */
public interface TestDiscoveryService {

    /**
     * Discovers the test implementation methods currently available to this service.
     *
     * @return the discovered implementations; may be empty if none are found, but never null
     */
    List<DiscoveredImplementation> discover();
}
