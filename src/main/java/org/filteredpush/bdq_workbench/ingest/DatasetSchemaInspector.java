/** DatasetSchemaInspector.java
 *
 * Lightweight schema inspection for dataset-view setup.
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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.filteredpush.bdq_workbench.app.AppException;

/**
 * Inspects dataset table metadata without reading table rows.
 */
public class DatasetSchemaInspector {
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Inspects available dataset tables for UI decisions.
	 *
	 * @param inputPath dataset input path
	 * @return metadata-only table summary
	 */
	public DatasetSchemaOverview inspect(Path inputPath) {
		String fileName = inputPath.getFileName().toString().toLowerCase();
		if (fileName.endsWith(".zip")) {
			return inspectDwcArchive(inputPath);
		}
		if (fileName.endsWith(".json") || fileName.endsWith("datapackage")) {
			return inspectDataPackage(inputPath);
		}
		throw new AppException("Unsupported dataset input: " + inputPath);
	}

	private DatasetSchemaOverview inspectDwcArchive(Path inputPath) {
		try (ZipFile zipFile = new ZipFile(inputPath.toFile())) {
			List<CoreTableCandidate<DwcArchiveCoreMeta>> tables = DwcArchiveMetaParser.parseTables(zipFile);
			if (!tables.isEmpty()) {
				return new DatasetSchemaOverview(tables.stream()
						.map(table -> new DatasetTableSummary(
								table.label(),
								table.rowType(),
								table.rowTypeEvidence(),
								table.declaredCore()))
						.toList());
			}
			return new DatasetSchemaOverview(List.of(new DatasetTableSummary(
					resolveConventionalCoreEntryName(zipFile),
					DatasetRowType.OCCURRENCE,
					"conventional default",
					true)));
		} catch (IOException e) {
			throw new AppException("Failed to inspect DwC-A " + inputPath, e);
		}
	}

	private DatasetSchemaOverview inspectDataPackage(Path inputPath) {
		try {
			JsonNode root = mapper.readTree(Files.newBufferedReader(inputPath));
			Path packageDir = inputPath.toAbsolutePath().getParent();
			List<CoreTableCandidate<DataPackageResourceMeta>> tables =
					DataPackageDialectParser.parseResources(mapper, root, packageDir);
			return new DatasetSchemaOverview(tables.stream()
					.map(table -> new DatasetTableSummary(
							table.label(),
							table.rowType(),
							table.rowTypeEvidence(),
							table.declaredCore()))
					.toList());
		} catch (IOException e) {
			throw new AppException("Failed to inspect data package " + inputPath, e);
		}
	}

	private String resolveConventionalCoreEntryName(ZipFile zipFile) {
		ZipEntry occurrence = zipFile.getEntry("occurrence.txt");
		if (occurrence != null) {
			return occurrence.getName();
		}
		return zipFile.stream()
				.filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".txt"))
				.map(ZipEntry::getName)
				.findFirst()
				.orElse("occurrence.txt");
	}

	/**
	 * Metadata-only dataset summary.
	 *
	 * @param tables offered tables
	 */
	public record DatasetSchemaOverview(List<DatasetTableSummary> tables) {

		/**
		 * Canonical constructor; copies list defensively.
		 */
		public DatasetSchemaOverview {
			tables = List.copyOf(tables);
		}

		/**
		 * Renders a short table list for user-facing messages.
		 *
		 * @return one-line table summary
		 */
		public String describeTables() {
			if (tables.isEmpty()) {
				return "Dataset offers no readable tables";
			}
			if (tables.size() == 1) {
				return "Dataset offers one table, " + tables.get(0).describe();
			}
			return "Dataset offers " + tables.size() + " tables";
		}
	}

	/**
	 * Summary of one offered table.
	 *
	 * @param label table label
	 * @param rowType inferred row type
	 * @param rowTypeEvidence evidence for inferred row type
	 * @param declaredCore whether this table is the declared core
	 */
	public record DatasetTableSummary(
			String label,
			DatasetRowType rowType,
			String rowTypeEvidence,
			boolean declaredCore) {

		/**
		 * Renders one table as a short human-readable descriptor.
		 *
		 * @return rendered table descriptor
		 */
		public String describe() {
			String declared = declaredCore ? ", declared core" : "";
			return label + " [rowType=" + rowType.name() + " (" + rowTypeEvidence + ")" + declared + "]";
		}
	}
}
