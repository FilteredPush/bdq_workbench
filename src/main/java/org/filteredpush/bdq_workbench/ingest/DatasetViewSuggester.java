/** DatasetViewSuggester.java
 *
 * Builds editable dataset-view suggestions from discovered schema metadata.
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;
import org.filteredpush.bdq_workbench.model.TableSchema;

/**
 * Suggests a reusable {@link DatasetView} from a relational schema and a requested term set.
 */
public class DatasetViewSuggester {

	/**
	 * Chooses a default grain table, preferring occurrence rows and then other recognized core row
	 * types before falling back to the first declared table.
	 *
	 * @param schema discovered schema
	 * @return the default grain table name
	 */
	public String defaultGrainTable(DatasetSchema schema) {
		return schema.tables().stream()
				.filter(table -> "OCCURRENCE".equalsIgnoreCase(table.rowType()))
				.findFirst()
				.or(() -> schema.tables().stream()
						.filter(table -> "TAXON".equalsIgnoreCase(table.rowType())
								|| "EVENT".equalsIgnoreCase(table.rowType()))
						.findFirst())
				.or(() -> schema.tables().stream().findFirst())
				.map(TableSchema::name)
				.orElse("core");
	}

	/**
	 * Lists the joins that can be reached directly from the requested grain table.
	 *
	 * @param schema discovered schema
	 * @param grainTable selected grain table
	 * @return join candidates, oriented relative to the grain
	 */
	public List<JoinCandidate> joinCandidates(DatasetSchema schema, String grainTable) {
		List<JoinCandidate> candidates = new ArrayList<>();
		for (RelationshipSchema relationship : schema.relationships()) {
			if (relationship.toTable().equalsIgnoreCase(grainTable)) {
				candidates.add(new JoinCandidate(
						relationship.fromTable(),
						relationship.fromTable(),
						relationship.fromColumn(),
						relationship.toTable(),
						relationship.toColumn()));
			} else if (relationship.fromTable().equalsIgnoreCase(grainTable)) {
				candidates.add(new JoinCandidate(
						relationship.toTable(),
						relationship.toTable(),
						relationship.toColumn(),
						relationship.fromTable(),
						relationship.fromColumn()));
			}
		}
		return candidates;
	}

	/**
	 * Suggests an editable dataset view for the chosen grain table and requested terms.
	 *
	 * @param schema discovered schema
	 * @param grainTable selected grain table
	 * @param requestedTerms requested Darwin Core terms
	 * @return suggested dataset view
	 */
	public DatasetView suggest(DatasetSchema schema, String grainTable, List<String> requestedTerms) {
		List<JoinCandidate> joinCandidates = joinCandidates(schema, grainTable);
		List<DatasetViewJoin> joins = joinCandidates.stream()
				.map(candidate -> new DatasetViewJoin(
						candidate.relationName(),
						candidate.sourceTable(),
						DatasetViewCardinalityPolicy.REJECT))
				.toList();
		Set<String> allowedSourceTables = new LinkedHashSet<>();
		allowedSourceTables.add(grainTable);
		joinCandidates.forEach(candidate -> allowedSourceTables.add(candidate.sourceTable()));
		List<DatasetViewMapping> mappings = new ArrayList<>();
		List<String> terms = requestedTerms.isEmpty()
				? List.of("occurrenceID", "scientificName", "eventDate", "decimalLatitude", "decimalLongitude")
				: requestedTerms;
		for (String term : terms) {
			ColumnMatch match = bestMatch(schema, allowedSourceTables, grainTable, term);
			if (match != null) {
				mappings.add(new DatasetViewMapping(term, match.sourceTable(), match.sourceColumn()));
			}
		}
		return new DatasetView(grainTable, schema.schemaFingerprint(), joins, mappings);
	}

	/**
	 * Returns the column names the given schema table exposes.
	 *
	 * @param schema discovered schema
	 * @param tableName table name
	 * @return declared columns, or an empty list when the table is unknown
	 */
	public List<String> columnsForTable(DatasetSchema schema, String tableName) {
		return schema.tables().stream()
				.filter(table -> table.name().equalsIgnoreCase(tableName))
				.findFirst()
				.map(TableSchema::columns)
				.orElse(List.of());
	}

	/**
	 * Finds the best source-table/column pair for one requested Darwin Core term.
	 *
	 * @param schema discovered schema
	 * @param allowedSourceTables tables currently included in the view
	 * @param grainTable selected grain table
	 * @param requestedTerm requested Darwin Core term
	 * @return the best matching source column, or {@code null} if none matched
	 */
	private ColumnMatch bestMatch(
			DatasetSchema schema,
			Set<String> allowedSourceTables,
			String grainTable,
			String requestedTerm) {
		Map<String, Integer> sourcePriority = new LinkedHashMap<>();
		int priority = 0;
		sourcePriority.put(grainTable.toLowerCase(), priority++);
		for (String table : allowedSourceTables) {
			sourcePriority.putIfAbsent(table.toLowerCase(), priority++);
		}
		ColumnMatch best = null;
		for (TableSchema table : schema.tables()) {
			if (!allowedSourceTables.contains(table.name())) {
				continue;
			}
			String matchedColumn = findColumn(table.columns(), requestedTerm);
			if (matchedColumn == null) {
				continue;
			}
			int score = sourcePriority.getOrDefault(table.name().toLowerCase(), Integer.MAX_VALUE);
			ColumnMatch candidate = new ColumnMatch(table.name(), matchedColumn, score);
			if (best == null
					|| candidate.score() < best.score()
					|| candidate.score() == best.score()
							&& candidate.sourceColumn().compareToIgnoreCase(best.sourceColumn()) < 0) {
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * Resolves one requested term against a table's declared columns.
	 *
	 * @param columns declared columns
	 * @param requestedTerm requested Darwin Core term
	 * @return the preferred matching column, or {@code null} if none match
	 */
	private String findColumn(List<String> columns, String requestedTerm) {
		Map<String, List<String>> aliases = DarwinCoreTermResolver.indexAvailableTerms(columns);
		DarwinCoreTermResolver.Resolution resolution = DarwinCoreTermResolver.resolve(requestedTerm, aliases);
		return resolution.preferredMatch();
	}

	/**
	 * One candidate join relative to the chosen grain table.
	 *
	 * @param relationName relation key used on {@link org.filteredpush.bdq_workbench.model.RecordGraph}
	 * @param sourceTable related table whose values may be mapped
	 * @param sourceColumn related-table join column
	 * @param targetTable grain-side table the relation points to
	 * @param targetColumn grain-side join column
	 */
	public record JoinCandidate(
			String relationName,
			String sourceTable,
			String sourceColumn,
			String targetTable,
			String targetColumn) {
	}

	/**
	 * One scored source-column match.
	 *
	 * @param sourceTable matched table
	 * @param sourceColumn matched column
	 * @param score lower is better
	 */
	private record ColumnMatch(String sourceTable, String sourceColumn, int score) {
	}
}
