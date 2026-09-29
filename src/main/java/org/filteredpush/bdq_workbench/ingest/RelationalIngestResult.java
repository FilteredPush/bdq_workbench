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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.RecordGraph;

/**
 * Relational ingest output.
 *
 * @param graphs core-record graphs in deterministic row order
 * @param schema discovered schema metadata and fingerprint
 * @param diagnostics non-fatal ingest diagnostics
 * @param coreTable the name of the table whose rows became the graphs' core records; {@code ""}
 *     when not recorded
 * @param tableRecordCounts the number of rows read from each table, keyed by table name in
 *     declaration order; empty when not recorded
 */
public record RelationalIngestResult(
		List<RecordGraph> graphs,
		DatasetSchema schema,
		List<String> diagnostics,
		String coreTable,
		Map<String, Integer> tableRecordCounts) {

	/**
	 * Creates a result with no recorded core table name or per-table row counts.
	 *
	 * @param graphs core-record graphs in deterministic row order
	 * @param schema discovered schema metadata and fingerprint
	 * @param diagnostics non-fatal ingest diagnostics
	 */
	public RelationalIngestResult(List<RecordGraph> graphs, DatasetSchema schema, List<String> diagnostics) {
		this(graphs, schema, diagnostics, "", Map.of());
	}

	/**
	 * Canonical constructor; copies list and map components defensively.
	 */
	public RelationalIngestResult {
		graphs = List.copyOf(graphs);
		diagnostics = List.copyOf(diagnostics);
		coreTable = coreTable == null ? "" : coreTable;
		tableRecordCounts = Collections.unmodifiableMap(new LinkedHashMap<>(
				tableRecordCounts == null ? Map.of() : tableRecordCounts));
	}
}
