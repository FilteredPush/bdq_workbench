/** AppConfig.java
 *
 * Immutable configuration values controlling a single BDQ Workbench run.
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
package org.filteredpush.bdq_workbench.app;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable application configuration values.
 *
 * <p>Produced by {@link ConfigLoader} from classpath defaults and command line overrides, and
 * consumed by {@link BdqWorkbenchApplication} and the GUI to wire up the pipeline services
 * (use case resolution, test discovery, and parallel execution) for a run.
 *
 * @param useCaseXml path to the use case XML definition file
 * @param rdfDefinitions RDF/OWL files (e.g. {@code bdqtest.ttl}, {@code bdqffdq.owl}) used to
 *     resolve test/dimension/method metadata referenced by the use case
 * @param datasetPath path to the input dataset, either a Darwin Core Archive zip or a
 *     datapackage.json
 * @param useCaseId optional identifier selecting a specific use case within {@code useCaseXml};
 *     empty to use the default/only use case
 * @param implementationPackages Java package names to scan for annotated test implementations
 * @param threadCount number of worker threads used for parallel test execution, must be at
 *     least 1
 * @param dedupEnabled whether to invoke each test once per distinct combination of values of the
 *     Darwin Core terms it declares as input, rather than once per record, applying the result to
 *     every record sharing that combination; defaults to {@code true}
 */
public record AppConfig(
        Path useCaseXml,
        List<Path> rdfDefinitions,
        Path datasetPath,
        String useCaseId,
        List<String> implementationPackages,
        int threadCount,
        boolean dedupEnabled) {
}
