/** DwcArchiveIngestor.java
 *
 * Ingests Darwin Core Archives (zipped, meta.xml-described delimited data files) into canonical records.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingests Darwin Core Archives into canonical records.
 *
 * <p>Opens the zip archive and, when it carries a {@code meta.xml} descriptor, parses the core
 * data file exactly as that descriptor declares it: the declared core file location(s), character
 * encoding, field delimiter, field encapsulation character, header line count and per-column
 * Darwin Core terms (see {@link DwcArchiveMetaParser}). Honoring the descriptor rather than
 * assuming a fixed tab-separated, double-quote-encapsulated layout is what lets archives that
 * declare {@code fieldsEnclosedBy=""} — the common case for published archives, GBIF downloads
 * included — carry bare {@code "} characters in their data without the parse failing partway
 * through the file or silently merging records from the first offending row onward.
 *
 * <p>Archives without a usable {@code meta.xml} fall back to the previous convention: the
 * {@code occurrence.txt} entry, else the first {@code .txt} entry found, read as UTF-8
 * tab-delimited text with a header row and lenient handling of stray characters around quoted
 * fields.
 *
 * <p>Each row's record ID is taken from an {@code id} or {@code occurrenceID} column, falling
 * back to a synthesized {@code row-<n>} identifier; when a descriptor declares several core data
 * files, those synthesized identifiers restart at each file.
 */
public class DwcArchiveIngestor {
	private static final Logger LOG = LoggerFactory.getLogger(DwcArchiveIngestor.class);

	/**
	 * Ingests a Darwin Core Archive into canonical records.
	 *
	 * @param archivePath path to the zipped Darwin Core Archive
	 * @return the dataset parsed from the archive's core data file(s)
	 * @throws AppException if the archive cannot be read or has no core data file
	 */
	public RecordDataset ingest(Path archivePath) {
		try (ZipFile zipFile = new ZipFile(archivePath.toFile())) {
			Optional<DwcArchiveCoreMeta> coreMeta = DwcArchiveMetaParser.parseCoreMeta(zipFile)
					.filter(meta -> hasResolvableLocation(zipFile, meta));
			if (coreMeta.isPresent()) {
				return ingestDescribedCore(zipFile, archivePath, coreMeta.get());
			}
			return ingestConventionalCore(zipFile, archivePath);
		} catch (IOException e) {
			throw new AppException("Failed to ingest DwC-A from " + archivePath, e);
		}
	}

	/**
	 * Ingests the core data file(s) described by the archive's {@code meta.xml}.
	 *
	 * @param zipFile the open archive
	 * @param archivePath path of the archive, for error messages
	 * @param coreMeta the parsed core file descriptor
	 * @return the dataset parsed from every declared core data file, in declaration order
	 */
	private RecordDataset ingestDescribedCore(ZipFile zipFile, Path archivePath, DwcArchiveCoreMeta coreMeta) {
		CSVFormat csvFormat = buildDescribedFormat(coreMeta);
		List<CanonicalRecord> records = new ArrayList<>();
		for (String location : coreMeta.locations()) {
			ZipEntry entry = zipFile.getEntry(location);
			if (entry == null) {
				LOG.warn("Archive meta.xml declares core data file '{}', which is not present in {}",
						location, archivePath);
				continue;
			}
			records.addAll(readEntry(zipFile, archivePath, entry, csvFormat, coreMeta).records());
		}
		applyConstantTerms(records, coreMeta);
		return new RecordDataset(records);
	}

	/**
	 * Ingests the core data file by file-name convention, for archives without a usable
	 * {@code meta.xml}.
	 *
	 * @param zipFile the open archive
	 * @param archivePath path of the archive, for error messages
	 * @return the dataset parsed from the conventional core data file
	 */
	private RecordDataset ingestConventionalCore(ZipFile zipFile, Path archivePath) {
		ZipEntry core = resolveCoreDataEntry(zipFile);
		CSVFormat csvFormat = CSVFormat.TDF.builder()
				.setHeader()
				.setSkipHeaderRecord(true)
				.setTrailingData(true)
				.setLenientEof(true)
				.build();
		return readEntry(zipFile, archivePath, core, csvFormat, null);
	}

	/**
	 * Reads one core data entry into canonical records.
	 *
	 * @param zipFile the open archive
	 * @param archivePath path of the archive, for error messages
	 * @param entry the core data entry to read
	 * @param csvFormat the format to parse the entry with
	 * @param coreMeta the core file descriptor, or {@code null} when reading by convention
	 * @return the dataset parsed from the entry
	 * @throws AppException if the entry cannot be read or parsed, naming the entry so a malformed
	 *     core data file can be told apart from a malformed archive
	 */
	private RecordDataset readEntry(ZipFile zipFile, Path archivePath, ZipEntry entry, CSVFormat csvFormat,
			DwcArchiveCoreMeta coreMeta) {
		Charset encoding = coreMeta == null ? StandardCharsets.UTF_8 : coreMeta.encoding();
		int linesToSkip = linesToSkipBeforeParsing(csvFormat, coreMeta);
		try {
			return DelimitedRecordReader.read(
					() -> openEntryReader(zipFile, entry, encoding, linesToSkip),
					csvFormat);
		} catch (IOException e) {
			throw new AppException("Failed to parse core data file '" + entry.getName() + "' in " + archivePath
					+ ": " + e.getMessage(), e);
		}
	}

	/**
	 * Determines how many leading lines to consume before handing the reader to Commons CSV.
	 *
	 * <p>Commons CSV can skip at most one header record itself, so any further header lines a
	 * descriptor declares are consumed from the reader first.
	 *
	 * @param csvFormat the format the entry will be parsed with
	 * @param coreMeta the core file descriptor, or {@code null} when reading by convention
	 * @return the number of lines to consume before parsing
	 */
	private int linesToSkipBeforeParsing(CSVFormat csvFormat, DwcArchiveCoreMeta coreMeta) {
		if (coreMeta == null) {
			return 0;
		}
		return csvFormat.getSkipHeaderRecord() ? coreMeta.ignoreHeaderLines() - 1 : coreMeta.ignoreHeaderLines();
	}

	/**
	 * Builds the Commons CSV format described by an archive's {@code meta.xml}.
	 *
	 * <p>When the descriptor names the columns, those names are used as the header and every line
	 * of the file is treated as data (any header lines having already been consumed from the
	 * reader). When it does not, the file's own first line is used as the header, which requires
	 * the descriptor to declare at least one header line.
	 *
	 * @param coreMeta the core file descriptor
	 * @return the configured format
	 */
	private CSVFormat buildDescribedFormat(DwcArchiveCoreMeta coreMeta) {
		boolean namedByDescriptor = !coreMeta.columnNames().isEmpty();
		CSVFormat.Builder builder = CSVFormat.DEFAULT.builder()
				.setDelimiter(coreMeta.fieldsTerminatedBy())
				.setQuote(coreMeta.fieldsEnclosedBy())
				.setTrailingData(true)
				.setLenientEof(true)
				.setAllowMissingColumnNames(true)
				.setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL);
		if (namedByDescriptor) {
			return builder
					.setHeader(coreMeta.columnNames().toArray(new String[0]))
					.setSkipHeaderRecord(false)
					.build();
		}
		LOG.warn("Archive meta.xml names no core columns; using the core data file's first line as its header");
		return builder
				.setHeader()
				.setSkipHeaderRecord(true)
				.build();
	}

	/**
	 * Adds the descriptor's constant term values to every record.
	 *
	 * <p>A {@code meta.xml} {@code <field>} with a {@code default} value but no {@code index}
	 * declares a term that is the same for every record and carried in the descriptor rather than
	 * in the data file. Values already present in a record take precedence.
	 *
	 * @param records the records to augment, modified in place
	 * @param coreMeta the core file descriptor
	 */
	private void applyConstantTerms(List<CanonicalRecord> records, DwcArchiveCoreMeta coreMeta) {
		if (coreMeta.constantTerms().isEmpty()) {
			return;
		}
		for (CanonicalRecord record : records) {
			coreMeta.constantTerms().forEach(record.terms()::putIfAbsent);
		}
	}

	/**
	 * Opens a reader over one core data entry, positioned at its first data line.
	 *
	 * <p>Consumes a leading byte order mark if present, then the requested number of header lines.
	 *
	 * @param zipFile the open archive
	 * @param entry the entry to read
	 * @param encoding the entry's character encoding
	 * @param linesToSkip the number of leading lines to consume
	 * @return a reader positioned at the entry's first data line
	 * @throws IOException if the entry cannot be opened or read
	 */
	private BufferedReader openEntryReader(ZipFile zipFile, ZipEntry entry, Charset encoding, int linesToSkip)
			throws IOException {
		InputStream in = zipFile.getInputStream(entry);
		return DelimitedRecordReader.prepare(new BufferedReader(new InputStreamReader(in, encoding)), linesToSkip);
	}

	/**
	 * Reports whether any core data file a descriptor declares is actually present in the archive.
	 *
	 * @param zipFile the open archive
	 * @param coreMeta the parsed core file descriptor
	 * @return {@code true} if at least one declared location resolves to an archive entry
	 */
	private boolean hasResolvableLocation(ZipFile zipFile, DwcArchiveCoreMeta coreMeta) {
		boolean resolvable = coreMeta.locations().stream().anyMatch(location -> zipFile.getEntry(location) != null);
		if (!resolvable) {
			LOG.warn("No core data file declared by the archive's meta.xml ({}) is present in the archive; "
					+ "falling back to default core file conventions", coreMeta.locations());
		}
		return resolvable;
	}

	/**
	 * Locates the archive's core data entry by convention, preferring {@code occurrence.txt} and
	 * otherwise falling back to the first non-directory {@code .txt} entry found.
	 *
	 * @param zipFile the open archive to search
	 * @return the core data zip entry
	 * @throws AppException if no {@code .txt} entry is found in the archive
	 */
	private ZipEntry resolveCoreDataEntry(ZipFile zipFile) {
		ZipEntry occurrence = zipFile.getEntry("occurrence.txt");
		if (occurrence != null) {
			return occurrence;
		}
		return zipFile.stream()
				.filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".txt"))
				.findFirst()
				.orElseThrow(() -> new AppException("No core data text file found in archive"));
	}
}
