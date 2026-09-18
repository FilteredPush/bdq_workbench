/** RecordFilterService.java
 *
 * Contract for applying pre-execution record filters to an ingested canonical dataset.
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
package org.filteredpush.bdq_workbench.filtering;

import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordFilterSpec;
import org.filteredpush.bdq_workbench.model.RecordFilterSummary;

/**
 * Applies a configured {@link RecordFilterSpec} to an ingested {@link RecordDataset}.
 */
public interface RecordFilterService {

	/**
	 * Applies {@code filterSpec} to {@code dataset}, returning the selected records together with
	 * counts and diagnostics describing the filtering outcome.
	 *
	 * @param dataset the ingested dataset
	 * @param filterSpec the configured record filter criteria
	 * @return the filtered dataset summary
	 */
	RecordFilterSummary apply(RecordDataset dataset, RecordFilterSpec filterSpec);
}
