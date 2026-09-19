/** DataPackageIngestor.java
 *
 * Ingests Frictionless-style Darwin Core Data Packages (a datapackage.json manifest referencing a tabular resource) into canonical records.
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
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingests Darwin Core Data Packages into canonical records.
 *
 * <p>Reads the {@code datapackage.json} manifest at the given path and parses its first declared
 * resource exactly as that resource declares itself: data file path (single or multipart),
 * character encoding, table dialect (delimiter, quote character, escape character, comment
 * marker, null sequence, header layout) and table schema field names — see
 * {@link DataPackageDialectParser}. This is the Data Package counterpart of honoring a Darwin
 * Core Archive's {@code meta.xml}, and matters for the same reason: a resource declaring
 * {@code "quoteChar": ""} or a non-comma delimiter fails partway through the file, or silently
 * merges records, if it is read as default comma-separated, double-quote-encapsulated CSV.
 *
 * <p>Anything a resource leaves unstated falls back to the Frictionless table dialect defaults,
 * which are the comma-delimited, quoted, header-row conventions the workbench assumed before
 * dialects were honored.
 *
 * <p>Each row's record ID is taken from an {@code id} or {@code occurrenceID} column, falling
 * back to a synthesized {@code row-<n>} identifier; for a multipart resource those synthesized
 * identifiers restart at each part.
 */
public class DataPackageIngestor {
	private static final Logger LOG = LoggerFactory.getLogger(DataPackageIngestor.class);

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Ingests a Darwin Core Data Package into canonical records.
	 *
	 * @param dataPackagePath path to the {@code datapackage.json} manifest file
	 * @return the dataset parsed from the manifest's first resource
	 * @throws AppException if the manifest has no resources, or the resource cannot be read
	 */
	public RecordDataset ingest(Path dataPackagePath) {
		try {
			JsonNode root = mapper.readTree(Files.newBufferedReader(dataPackagePath));
			JsonNode resources = root.path("resources");
			if (!resources.isArray() || resources.isEmpty()) {
				throw new AppException("Data package does not include resources");
			}
			Path packageDir = dataPackagePath.toAbsolutePath().getParent();
			DataPackageResourceMeta resourceMeta =
					DataPackageDialectParser.parseResource(mapper, resources.get(0), packageDir);
			return ingestResource(dataPackagePath, resourceMeta);
		} catch (IOException e) {
			throw new AppException("Failed to ingest Darwin Core Data Package from " + dataPackagePath, e);
		}
	}

	/**
	 * Parses every data file of a resource into canonical records.
	 *
	 * @param dataPackagePath path of the manifest, for error messages
	 * @param resourceMeta the parsed resource descriptor
	 * @return the dataset parsed from the resource's data files, in declaration order
	 */
	private RecordDataset ingestResource(Path dataPackagePath, DataPackageResourceMeta resourceMeta) {
		CSVFormat csvFormat = buildResourceFormat(resourceMeta);
		int linesToSkip = resourceMeta.namesColumnsFromHeaderLine()
				? resourceMeta.headerLines() - 1
				: resourceMeta.headerLines();
		List<CanonicalRecord> records = new ArrayList<>();
		for (Path dataPath : resourceMeta.paths()) {
			records.addAll(readDataFile(dataPackagePath, dataPath, resourceMeta, csvFormat, linesToSkip).records());
		}
		return new RecordDataset(records);
	}

	/**
	 * Reads one of a resource's data files into canonical records.
	 *
	 * @param dataPackagePath path of the manifest, for error messages
	 * @param dataPath path of the data file to read
	 * @param resourceMeta the parsed resource descriptor
	 * @param csvFormat the format to parse the data file with
	 * @param linesToSkip the number of leading lines to consume before parsing
	 * @return the dataset parsed from the data file
	 * @throws AppException if the data file cannot be read or parsed, naming the file so a
	 *     malformed data file can be told apart from a malformed manifest
	 */
	private RecordDataset readDataFile(Path dataPackagePath, Path dataPath, DataPackageResourceMeta resourceMeta,
			CSVFormat csvFormat, int linesToSkip) {
		try {
			return DelimitedRecordReader.read(() -> openDataReader(dataPath, resourceMeta, linesToSkip), csvFormat);
		} catch (IOException e) {
			throw new AppException("Failed to parse data file '" + dataPath + "' of data package "
					+ dataPackagePath + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Builds the Commons CSV format described by a resource's dialect and schema.
	 *
	 * <p>When the table schema names the columns, those names are used as the header and every
	 * line of the file is treated as data, any header lines having already been consumed from the
	 * reader. When it does not, the file's own header line supplies the names; a resource that
	 * declares neither a schema nor a header gets positional placeholder names.
	 *
	 * @param resourceMeta the parsed resource descriptor
	 * @return the configured format
	 */
	private CSVFormat buildResourceFormat(DataPackageResourceMeta resourceMeta) {
		CSVFormat.Builder builder = baseFormatBuilder(resourceMeta);
		if (!resourceMeta.columnNames().isEmpty()) {
			return builder
					.setHeader(resourceMeta.columnNames().toArray(new String[0]))
					.setSkipHeaderRecord(false)
					.build();
		}
		if (resourceMeta.headerLines() > 0) {
			return builder.setHeader().setSkipHeaderRecord(true).build();
		}
		return builder
				.setHeader(positionalColumnNames(resourceMeta))
				.setSkipHeaderRecord(false)
				.build();
	}

	/**
	 * Builds the dialect-derived part of a resource's format, without header configuration.
	 *
	 * @param resourceMeta the parsed resource descriptor
	 * @return a builder carrying the resource's dialect
	 */
	private CSVFormat.Builder baseFormatBuilder(DataPackageResourceMeta resourceMeta) {
		return CSVFormat.DEFAULT.builder()
				.setDelimiter(resourceMeta.delimiter())
				.setQuote(resourceMeta.quoteChar())
				.setEscape(resourceMeta.escapeChar())
				.setCommentMarker(resourceMeta.commentChar())
				.setNullString(resourceMeta.nullSequence())
				.setIgnoreSurroundingSpaces(resourceMeta.skipInitialSpace())
				.setTrailingData(true)
				.setLenientEof(true)
				.setAllowMissingColumnNames(true)
				.setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL);
	}

	/**
	 * Synthesizes positional column names for a resource that declares neither a table schema nor
	 * a header line, by counting the columns in its first record.
	 *
	 * @param resourceMeta the parsed resource descriptor
	 * @return placeholder column names, one per column of the first record
	 */
	private String[] positionalColumnNames(DataPackageResourceMeta resourceMeta) {
		LOG.warn("Data package resource declares neither a table schema nor a header line;"
				+ " naming its columns positionally");
		int columnCount = countColumns(resourceMeta);
		String[] columnNames = new String[columnCount];
		for (int index = 0; index < columnCount; index++) {
			columnNames[index] = "column-" + index;
		}
		return columnNames;
	}

	/**
	 * Counts the columns in the first record of a resource's first data file.
	 *
	 * @param resourceMeta the parsed resource descriptor
	 * @return the number of columns, or zero if the count cannot be determined
	 */
	private int countColumns(DataPackageResourceMeta resourceMeta) {
		Path firstPath = resourceMeta.paths().get(0);
		try {
			return DelimitedRecordReader.countFirstRecordColumns(
					() -> openDataReader(firstPath, resourceMeta, 0),
					baseFormatBuilder(resourceMeta).build());
		} catch (IOException e) {
			throw new AppException("Failed to determine the column count of data file '" + firstPath + "'", e);
		}
	}

	/**
	 * Opens a reader over one data file, positioned at its first data line.
	 *
	 * @param dataPath path of the data file
	 * @param resourceMeta the parsed resource descriptor, supplying the character encoding
	 * @param linesToSkip the number of leading lines to consume
	 * @return a reader positioned at the data file's first data line
	 * @throws IOException if the file cannot be opened or read
	 */
	private BufferedReader openDataReader(Path dataPath, DataPackageResourceMeta resourceMeta, int linesToSkip)
			throws IOException {
		return DelimitedRecordReader.prepare(
				Files.newBufferedReader(dataPath, resourceMeta.encoding()), linesToSkip);
	}
}
