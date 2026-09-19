/** WorkbenchDefaults.java
 *
 * Default resource sources and use case selection shared by the GUI and the command line runner.
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

import java.util.List;
import org.filteredpush.bdq_workbench.model.UseCase;

/**
 * Defaults shared by the GUI and the command line runner.
 *
 * <p>The GUI has always defaulted to the ratified sources published at {@code bdq.tdwg.org},
 * fetched and cached by {@link CachedResourceResolver}, and to a sensible preselected use case.
 * Holding those decisions here rather than in {@link BdqWorkbenchGui} is what lets a command
 * line run start from nothing but {@code --dataset}: both entry points draw their defaults from
 * the same place, so a headless run behaves like a GUI run whose fields were left alone.
 */
public final class WorkbenchDefaults {

	/** Default source for the use case definitions. */
	public static final String USE_CASE_SOURCE = "https://bdq.tdwg.org/draft/dist/bdquc.xml";

	/** Default source for the BDQ test definitions. */
	public static final String TEST_DEFINITIONS_SOURCE = "https://bdq.tdwg.org/draft/dist/bdqtest.ttl";

	/** Default source for the bdqffdq ontology. */
	public static final String ONTOLOGY_SOURCE = "https://bdq.tdwg.org/draft/vocabulary/bdqffdq.ttl";

	/** Label of the use case preselected when nothing else picks one. */
	private static final String PREFERRED_USE_CASE_LABEL = "Spatial-Temporal Patterns";

	/** Utility class; not instantiable. */
	private WorkbenchDefaults() {
	}

	/**
	 * Chooses which use case a run should use when one was not explicitly configured.
	 *
	 * <p>An explicitly configured identifier wins when it names one of the available use cases.
	 * Otherwise the well-known {@value #PREFERRED_USE_CASE_LABEL} use case is preferred, and
	 * failing that the first one available.
	 *
	 * @param useCases the available use cases
	 * @param configuredUseCaseId explicitly configured use case identifier, if any
	 * @return the preferred use case identifier, or {@code ""} if no use cases are available
	 */
	public static String preferredUseCaseId(List<UseCase> useCases, String configuredUseCaseId) {
		if (configuredUseCaseId != null && !configuredUseCaseId.isBlank()) {
			for (UseCase useCase : useCases) {
				if (configuredUseCaseId.equals(useCase.id())) {
					return useCase.id();
				}
			}
		}
		for (UseCase useCase : useCases) {
			String label = useCase.label();
			if (label != null && PREFERRED_USE_CASE_LABEL.equalsIgnoreCase(label.trim())) {
				return useCase.id();
			}
		}
		return useCases.isEmpty() ? "" : useCases.get(0).id();
	}
}
