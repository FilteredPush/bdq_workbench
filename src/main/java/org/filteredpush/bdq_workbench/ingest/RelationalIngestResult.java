/** RelationalIngestResult.java
 *
 * Result of relational dataset ingest plus schema discovery.
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
package org.filteredpush.bdq_workbench.ingest;

import java.util.List;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.RecordGraph;

/**
 * Relational ingest output.
 *
 * @param graphs core-record graphs in deterministic row order
 * @param schema discovered schema metadata and fingerprint
 * @param diagnostics non-fatal ingest diagnostics
 */
public record RelationalIngestResult(
		List<RecordGraph> graphs,
		DatasetSchema schema,
		List<String> diagnostics) {

	/**
	 * Canonical constructor; copies list components defensively.
	 */
	public RelationalIngestResult {
		graphs = List.copyOf(graphs);
		diagnostics = List.copyOf(diagnostics);
	}
}
