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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
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
			JsonNode root = mapper.readTree(Files.newBufferedReader(inputPath));
			Path packageDir = inputPath.toAbsolutePath().getParent();
			List<CoreTableCandidate<DataPackageResourceMeta>> tables =
					DataPackageDialectParser.parseResources(mapper, root, packageDir);
			if (tables.isEmpty()) {
				return new RelationalIngestResult(List.of(), new DatasetSchema(List.of(), List.of(), ""), List.of());
			}
			CoreTableCandidate<DataPackageResourceMeta> selected = CoreTableSelector.select(tables, requestedTable).selected();
			Map<String, List<CanonicalRecord>> rowsByTable = new LinkedHashMap<>();
			for (CoreTableCandidate<DataPackageResourceMeta> table : tables) {
				List<CanonicalRecord> rows = dataPackageIngestor.ingest(inputPath, table.label()).records().stream()
						.map(row -> withTableProvenance(row, table.label()))
						.toList();
				rowsByTable.put(table.label(), rows);
			}
			List<RelationshipSchema> relationships = new ArrayList<>();
			for (CoreTableCandidate<DataPackageResourceMeta> table : tables) {
				for (DataPackageForeignKey key : table.descriptor().foreignKeys()) {
					String referencedTableLabel = resolveReferencedTableLabel(tables, table, key);
					if (referencedTableLabel == null || !referencedTableLabel.equals(selected.label())) {
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
			throw new AppException("Failed relational ingest for Data Package " + inputPath, e);
		}
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
		Map<String, Map<String, List<CanonicalRecord>>> relatedByTableByCoreId = new LinkedHashMap<>();
		for (RelationshipSchema relation : relationships) {
			Map<String, List<CanonicalRecord>> byCore = new LinkedHashMap<>();
			for (CanonicalRecord related : rowsByTable.getOrDefault(relation.fromTable(), List.of())) {
				String key = related.terms().getOrDefault(relation.fromColumn(), "");
				byCore.computeIfAbsent(key, ignored -> new ArrayList<>()).add(related);
			}
			relatedByTableByCoreId.put(relation.relationName(), byCore);
		}
		List<RecordGraph> graphs = new ArrayList<>();
		for (CanonicalRecord core : rowsByTable.getOrDefault(coreTable, List.of())) {
			Map<String, List<CanonicalRecord>> relatedByRelation = new LinkedHashMap<>();
			for (RelationshipSchema relation : relationships) {
				String coreValue = core.terms().getOrDefault(relation.toColumn(), "");
				if (coreValue.isBlank()) {
					continue;
				}
				List<CanonicalRecord> related = relatedByTableByCoreId
						.getOrDefault(relation.relationName(), Map.of())
						.getOrDefault(coreValue, List.of());
				if (related.isEmpty()) {
					diagnostics.add("No related rows found for relation " + relation.relationName()
							+ " and core record " + core.id());
				}
				relatedByRelation.put(relation.relationName(), related);
			}
			graphs.add(new RecordGraph(core, relatedByRelation));
		}
		String fingerprint = SchemaFingerprint.of(tables, relationships);
		return new RelationalIngestResult(graphs, new DatasetSchema(tables, relationships, fingerprint), diagnostics);
	}

	private CanonicalRecord withTableProvenance(CanonicalRecord row, String tableName) {
		Map<String, List<SourceCell>> provenance = new LinkedHashMap<>();
		row.terms().forEach((term, value) -> provenance.put(term, List.of(
				new SourceCell(tableName, tableName, row.id(), term, term))));
		return new CanonicalRecord(row.id(), row.terms(), provenance);
	}
}
