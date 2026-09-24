/** RelationalDatasetIngestor.java
 *
 * Builds RecordGraph structures from DwC-A/Data Package inputs.
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;
import org.filteredpush.bdq_workbench.model.SourceCell;
import org.filteredpush.bdq_workbench.model.TableSchema;

/**
 * Relational ingestion for non-flat datasets.
 */
public class RelationalDatasetIngestor {
	private final DwcArchiveIngestor dwcArchiveIngestor = new DwcArchiveIngestor();
	private final DataPackageIngestor dataPackageIngestor = new DataPackageIngestor();
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Ingests a dataset and emits relational graphs plus schema metadata.
	 *
	 * @param inputPath dataset path
	 * @param requestedTable optional preferred core/grain table
	 * @return relational ingest output
	 */
	public RelationalIngestResult ingest(Path inputPath, String requestedTable) {
		String fileName = inputPath.getFileName().toString().toLowerCase();
		if (fileName.endsWith(".zip")) {
			if (DataPackageArchiveSupport.isDataPackageArchive(inputPath)) {
				return ingestDataPackage(inputPath, requestedTable);
			}
			return ingestDwcArchive(inputPath, requestedTable);
		}
		if (fileName.endsWith(".json") || fileName.endsWith("datapackage")) {
			return ingestDataPackage(inputPath, requestedTable);
		}
		throw new AppException("Unsupported dataset input: " + inputPath);
	}

	private RelationalIngestResult ingestDwcArchive(Path inputPath, String requestedTable) {
		try (ZipFile zipFile = new ZipFile(inputPath.toFile())) {
			List<CoreTableCandidate<DwcArchiveCoreMeta>> tables = DwcArchiveMetaParser.parseTables(zipFile);
			if (tables.isEmpty()) {
				return new RelationalIngestResult(List.of(), new DatasetSchema(List.of(), List.of(), ""), List.of());
			}
			CoreTableCandidate<DwcArchiveCoreMeta> selected = CoreTableSelector.select(tables, requestedTable).selected();
			Map<String, List<CanonicalRecord>> rowsByTable = new LinkedHashMap<>();
			for (CoreTableCandidate<DwcArchiveCoreMeta> table : tables) {
				List<CanonicalRecord> rows = dwcArchiveIngestor.ingest(inputPath, table.label()).records().stream()
						.map(row -> withTableProvenance(row, table.label()))
						.toList();
				rowsByTable.put(table.label(), rows);
			}
			List<RelationshipSchema> relationships = tables.stream()
					.filter(table -> !table.descriptor().coreIdColumn().isBlank())
					.map(table -> new RelationshipSchema(
							table.label(),
							table.descriptor().coreIdColumn(),
							selected.label(),
							selected.descriptor().idColumn().isBlank()
									? selected.rowType().identifierTerm()
									: selected.descriptor().idColumn(),
							table.label()))
					.toList();
			return assembleResult(selected.label(), rowsByTable, tables.stream()
					.map(table -> new TableSchema(
							table.label(),
							table.label(),
							table.rowType().name(),
							table.descriptor().idColumn().isBlank()
									? table.rowType().identifierTerm()
									: table.descriptor().idColumn(),
							table.descriptor().columnNames()))
					.toList(), relationships);
		} catch (IOException e) {
			throw new AppException("Failed relational ingest for DwC-A " + inputPath, e);
		}
	}

	private RelationalIngestResult ingestDataPackage(Path inputPath, String requestedTable) {
		try {
			return DataPackageArchiveSupport.withManifestPath(inputPath,
					manifestPath -> relationalFromDataPackageManifest(manifestPath, inputPath, requestedTable));
		} catch (IOException e) {
			throw new AppException("Failed relational ingest for Data Package " + inputPath, e);
		}
	}

	/**
	 * Builds a relational ingest result from a resolved Data Package manifest.
	 *
	 * @param manifestPath resolved path to {@code datapackage.json}
	 * @param sourcePath original user-supplied dataset path
	 * @param requestedTable optional preferred core/grain table
	 * @return relational ingest output
	 * @throws IOException if the manifest cannot be read
	 */
	private RelationalIngestResult relationalFromDataPackageManifest(Path manifestPath, Path sourcePath,
			String requestedTable) throws IOException {
		JsonNode root = mapper.readTree(Files.newBufferedReader(manifestPath));
		Path packageDir = manifestPath.toAbsolutePath().getParent();
		List<CoreTableCandidate<DataPackageResourceMeta>> tables =
				DataPackageDialectParser.parseResources(mapper, root, packageDir);
		if (tables.isEmpty()) {
			return new RelationalIngestResult(List.of(), new DatasetSchema(List.of(), List.of(), ""), List.of());
		}
		CoreTableCandidate<DataPackageResourceMeta> selected = CoreTableSelector.select(tables, requestedTable).selected();
		Map<String, List<CanonicalRecord>> rowsByTable = new LinkedHashMap<>();
		for (CoreTableCandidate<DataPackageResourceMeta> table : tables) {
			List<CanonicalRecord> rows = dataPackageIngestor.ingest(sourcePath, table.label()).records().stream()
					.map(row -> withTableProvenance(row, table.label()))
					.toList();
			rowsByTable.put(table.label(), rows);
		}
		List<RelationshipSchema> relationships = new ArrayList<>();
		for (CoreTableCandidate<DataPackageResourceMeta> table : tables) {
			for (DataPackageForeignKey key : table.descriptor().foreignKeys()) {
				String referencedTableLabel = resolveReferencedTableLabel(tables, table, key);
				if (referencedTableLabel == null) {
					continue;
				}
				relationships.add(new RelationshipSchema(
						table.label(),
						key.field(),
						referencedTableLabel,
						key.referenceField(),
						table.label()));
			}
		}
		relationships.addAll(inferImplicitRelationships(tables, relationships));
		return assembleResult(selected.label(), rowsByTable, tables.stream()
				.map(table -> new TableSchema(
						table.label(),
						table.label(),
						table.rowType().name(),
						table.descriptor().idColumn().isBlank()
								? table.rowType().identifierTerm()
								: table.descriptor().idColumn(),
						table.descriptor().columnNames()))
				.toList(), relationships);
	}

	private String resolveReferencedTableLabel(List<CoreTableCandidate<DataPackageResourceMeta>> tables,
			CoreTableCandidate<DataPackageResourceMeta> source,
			DataPackageForeignKey key) {
		if (key.referenceResource().isBlank()) {
			return source.label();
		}
		return tables.stream()
				.filter(candidate -> candidate.descriptor().name().equalsIgnoreCase(key.referenceResource()))
				.map(CoreTableCandidate::label)
				.findFirst()
				.orElse(null);
	}

	private RelationalIngestResult assembleResult(String coreTable, Map<String, List<CanonicalRecord>> rowsByTable,
			List<TableSchema> tables, List<RelationshipSchema> relationships) {
		List<String> diagnostics = new ArrayList<>();
		List<ResolvedRelation> resolvedRelations = relationships.stream()
				.map(relation -> resolveRelationForCore(coreTable, relation))
				.filter(java.util.Objects::nonNull)
				.toList();
		Map<ResolvedRelation, Map<String, List<CanonicalRecord>>> relatedByLookup = new LinkedHashMap<>();
		for (ResolvedRelation resolved : resolvedRelations) {
			relatedByLookup.put(resolved, indexByColumn(
					rowsByTable.getOrDefault(resolved.relatedTable(), List.of()),
					resolved.relatedColumn()));
		}
		List<RecordGraph> graphs = new ArrayList<>();
		for (CanonicalRecord core : rowsByTable.getOrDefault(coreTable, List.of())) {
			Map<String, List<CanonicalRecord>> relatedByRelation = new LinkedHashMap<>();
			for (ResolvedRelation resolved : resolvedRelations) {
				String coreValue = core.terms().getOrDefault(resolved.coreColumn(), "");
				if (coreValue.isBlank()) {
					continue;
				}
				List<CanonicalRecord> related = relatedByLookup
						.getOrDefault(resolved, Map.of())
						.getOrDefault(coreValue, List.of());
				if (related.isEmpty()) {
					diagnostics.add("No related rows found for relation " + resolved.relationName()
							+ " and core record " + core.id());
				}
				relatedByRelation.put(resolved.relationName(), related);
			}
			graphs.add(new RecordGraph(core, relatedByRelation));
		}
		String fingerprint = SchemaFingerprint.of(tables, relationships);
		return new RelationalIngestResult(graphs, new DatasetSchema(tables, relationships, fingerprint), diagnostics);
	}

	/**
	 * Infers simple identifier-based relationships that a Data Package manifest omitted.
	 *
	 * <p>The first non-flat datasets in this project commonly relate an occurrence table to its
	 * event table via a shared Darwin Core identifier column such as {@code eventID} even when the
	 * manifest declares no explicit foreign key. Recognizing those links lets the dataset-view
	 * builder offer occurrence-grain mappings across the full relational graph instead of only the
	 * handful of columns on the selected table.
	 *
	 * @param tables discovered Data Package tables
	 * @param existing explicitly declared relationships
	 * @return inferred relationships that do not duplicate the explicit ones
	 */
	private List<RelationshipSchema> inferImplicitRelationships(
			List<CoreTableCandidate<DataPackageResourceMeta>> tables,
			List<RelationshipSchema> existing) {
		Set<String> existingKeys = new LinkedHashSet<>();
		existing.forEach(relation -> existingKeys.add(relationshipKey(
				relation.fromTable(), relation.fromColumn(), relation.toTable(), relation.toColumn())));
		List<RelationshipSchema> inferred = new ArrayList<>();
		for (CoreTableCandidate<DataPackageResourceMeta> source : tables) {
			Set<String> explicitSourceFields = source.descriptor().foreignKeys().stream()
					.map(DataPackageForeignKey::field)
					.map(RelationalDatasetIngestor::normalizedName)
					.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			for (CoreTableCandidate<DataPackageResourceMeta> target : tables) {
				String targetIdentifier = identifierColumn(target);
				if (targetIdentifier.isBlank()) {
					continue;
				}
				String sourceIdentifier = identifierColumn(source);
				for (String sourceColumn : source.descriptor().columnNames()) {
					if (source.label().equalsIgnoreCase(target.label())
							&& normalizedName(sourceColumn).equals(normalizedName(targetIdentifier))) {
						continue;
					}
					if (normalizedName(sourceColumn).equals(normalizedName(sourceIdentifier))) {
						continue;
					}
					if (!normalizedName(sourceColumn).equals(normalizedName(targetIdentifier))) {
						continue;
					}
					if (explicitSourceFields.contains(normalizedName(sourceColumn))) {
						continue;
					}
					String key = relationshipKey(source.label(), sourceColumn, target.label(), targetIdentifier);
					if (existingKeys.contains(key)) {
						continue;
					}
					existingKeys.add(key);
					inferred.add(new RelationshipSchema(
							source.label(),
							sourceColumn,
							target.label(),
							targetIdentifier,
							source.label()));
					break;
				}
			}
		}
		return inferred;
	}

	/**
	 * Resolves how one schema relationship should be traversed from the selected core table.
	 *
	 * @param coreTable selected relational-graph core table
	 * @param relation relationship schema
	 * @return the orientation to use, or {@code null} when the relationship does not touch the core
	 */
	private ResolvedRelation resolveRelationForCore(String coreTable, RelationshipSchema relation) {
		if (relation.toTable().equals(coreTable)) {
			return new ResolvedRelation(
					relation.fromTable(),
					relation.toColumn(),
					relation.fromColumn(),
					relation.fromTable());
		}
		if (relation.fromTable().equals(coreTable)) {
			return new ResolvedRelation(
					relation.toTable(),
					relation.fromColumn(),
					relation.toColumn(),
					relation.toTable());
		}
		return null;
	}

	/**
	 * Indexes rows by one join column.
	 *
	 * @param rows rows to index
	 * @param column join column
	 * @return rows grouped by their join-column values
	 */
	private Map<String, List<CanonicalRecord>> indexByColumn(List<CanonicalRecord> rows, String column) {
		Map<String, List<CanonicalRecord>> indexed = new LinkedHashMap<>();
		for (CanonicalRecord row : rows) {
			String key = row.terms().getOrDefault(column, "");
			indexed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
		}
		return indexed;
	}

	/**
	 * Returns the identifier column a table exposes, using the row-type fallback when necessary.
	 *
	 * @param table table candidate
	 * @return identifier column name, or {@code ""} when none can be inferred
	 */
	private String identifierColumn(CoreTableCandidate<DataPackageResourceMeta> table) {
		return table.descriptor().idColumn().isBlank()
				? table.rowType().identifierTerm()
				: table.descriptor().idColumn();
	}

	/**
	 * Builds a normalized relationship key for deduplication.
	 *
	 * @param fromTable source table
	 * @param fromColumn source column
	 * @param toTable target table
	 * @param toColumn target column
	 * @return normalized relationship key
	 */
	private String relationshipKey(String fromTable, String fromColumn, String toTable, String toColumn) {
		return normalizedName(fromTable) + "->" + normalizedName(fromColumn) + "->"
				+ normalizedName(toTable) + "->" + normalizedName(toColumn);
	}

	/**
	 * Normalizes a table or column name for case-insensitive relationship matching.
	 *
	 * @param value raw table or column name
	 * @return normalized local name
	 */
	private static String normalizedName(String value) {
		return DarwinCoreTermResolver.normalizeTerm(DarwinCoreTermResolver.localName(value));
	}

	private CanonicalRecord withTableProvenance(CanonicalRecord row, String tableName) {
		Map<String, List<SourceCell>> provenance = new LinkedHashMap<>();
		row.terms().forEach((term, value) -> provenance.put(term, List.of(
				new SourceCell(tableName, tableName, row.id(), term, term))));
		return new CanonicalRecord(row.id(), row.terms(), provenance);
	}

	/**
	 * Relationship traversal resolved relative to the selected graph core.
	 *
	 * @param relatedTable table whose rows will appear under the relation key
	 * @param coreColumn core-table column used to look up related rows
	 * @param relatedColumn related-table column used to match the core value
	 * @param relationName relation key stored on the graph
	 */
	private record ResolvedRelation(
			String relatedTable,
			String coreColumn,
			String relatedColumn,
			String relationName) {
	}
}
