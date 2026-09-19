/** DelimitedRecordReader.java
 *
 * Shared delimited-text ingestion helpers for DwC-A and Darwin Core Data Package inputs.
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
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.RecordDataset;

/**
 * Reads delimited text into canonical BDQ workbench records.
 *
 * <p>The reader delegates to Apache Commons CSV so valid CSV/TDF inputs retain the expected quote
 * and delimiter behavior, including embedded delimiters inside quoted fields. Callers are expected
 * to pass a format configured with the leniency appropriate for the source they are reading.
 */
final class DelimitedRecordReader {

	/** Unicode byte order mark, which some producers write at the start of a text file. */
	private static final int BYTE_ORDER_MARK = '﻿';

	/** Utility class; not instantiable. */
	private DelimitedRecordReader() {
	}

	/**
	 * Opens a fresh buffered reader for the same delimited source.
	 */
	@FunctionalInterface
	interface ReaderSupplier {
		/**
		 * Opens the source for reading from the beginning.
		 *
		 * @return a fresh buffered reader
		 * @throws IOException if the source cannot be opened
		 */
		BufferedReader open() throws IOException;
	}

	/**
	 * Positions a freshly opened reader at the first line the parser should see.
	 *
	 * <p>Consumes a leading byte order mark if the source has one, then the requested number of
	 * leading lines. Callers use this for header lines their format descriptor declares but
	 * Commons CSV cannot skip itself, and for sources whose column names come from a descriptor
	 * rather than from the file.
	 *
	 * @param reader a reader positioned at the start of the source; closed if preparation fails
	 * @param linesToSkip the number of leading lines to consume, zero or negative for none
	 * @return the same reader, positioned at the first line to parse
	 * @throws IOException if the source cannot be read
	 */
	static BufferedReader prepare(BufferedReader reader, int linesToSkip) throws IOException {
		try {
			reader.mark(1);
			if (reader.read() != BYTE_ORDER_MARK) {
				reader.reset();
			}
			for (int line = 0; line < linesToSkip; line++) {
				if (reader.readLine() == null) {
					break;
				}
			}
			return reader;
		} catch (IOException | RuntimeException e) {
			reader.close();
			throw e;
		}
	}

	/**
	 * Counts the columns in the source's first record, for sources that carry no header and no
	 * descriptor naming their columns.
	 *
	 * @param readerSupplier supplies a fresh reader for the source
	 * @param csvFormat a header-less format matching the source's dialect
	 * @return the number of columns in the first record, or zero if the source has no records
	 * @throws IOException if the source cannot be opened or read
	 */
	static int countFirstRecordColumns(ReaderSupplier readerSupplier, CSVFormat csvFormat) throws IOException {
		try (BufferedReader reader = readerSupplier.open();
				CSVParser parser = csvFormat.parse(reader)) {
			Iterator<CSVRecord> rows = parser.iterator();
			return rows.hasNext() ? rows.next().size() : 0;
		}
	}

	/**
	 * Reads a delimited source into canonical records.
	 *
	 * @param readerSupplier supplies a fresh reader for the source
	 * @param csvFormat primary Commons CSV format to use
	 * @return the parsed dataset
	 * @throws IOException if the source cannot be opened or read
	 */
	static RecordDataset read(ReaderSupplier readerSupplier, CSVFormat csvFormat) throws IOException {
		return read(readerSupplier, csvFormat, "");
	}

	/**
	 * Reads a delimited source into canonical records, taking record IDs from a named column.
	 *
	 * @param readerSupplier supplies a fresh reader for the source
	 * @param csvFormat primary Commons CSV format to use
	 * @param idColumn the column the source's descriptor declares as its record identifier, blank
	 *     when it declares none
	 * @return the parsed dataset
	 * @throws IOException if the source cannot be opened or read
	 */
	static RecordDataset read(ReaderSupplier readerSupplier, CSVFormat csvFormat, String idColumn)
			throws IOException {
		try {
			return readWithCommonsCsv(readerSupplier, csvFormat, idColumn);
		} catch (UncheckedIOException e) {
			throw e.getCause();
		} catch (RuntimeException e) {
			IOException ioCause = findIOException(e);
			if (ioCause != null) {
				throw ioCause;
			}
			throw e;
		}
	}

	/**
	 * Parses the source with Commons CSV.
	 *
	 * @param readerSupplier supplies a fresh reader
	 * @param csvFormat configured CSV format
	 * @param idColumn the column declared as the source's record identifier, possibly blank
	 * @return the parsed dataset
	 * @throws IOException if the source cannot be opened or parsed
	 */
	private static RecordDataset readWithCommonsCsv(ReaderSupplier readerSupplier, CSVFormat csvFormat,
			String idColumn) throws IOException {
		try (BufferedReader reader = readerSupplier.open();
				CSVParser parser = csvFormat.parse(reader)) {
			List<CanonicalRecord> records = new ArrayList<>();
			parser.forEach(row -> {
				Map<String, String> values = new LinkedHashMap<>();
				row.toMap().forEach((key, value) -> values.put(normalize(key), normalize(value)));
				records.add(new CanonicalRecord(resolveRecordId(values, row.getRecordNumber(), idColumn), values));
			});
			return new RecordDataset(records);
		}
	}

	/**
	 * Resolves the record ID using the standard workbench precedence.
	 *
	 * <p>A column the source's own descriptor declares as its identifier wins, so that a table
	 * of something other than occurrences — an event or taxon table, whose identifier is
	 * {@code eventID} or {@code taxonID} — still yields meaningful record IDs. Otherwise the
	 * conventional {@code id} and {@code occurrenceID} columns are tried, and failing those the
	 * record's position in the source is synthesized into an identifier.
	 *
	 * <p>A column that is present but empty counts as absent at every step. Published archives
	 * do carry an {@code id} column they never populated, and taking it at face value would give
	 * every record in such a dataset the same empty identifier, collapsing them together
	 * wherever a report keys on the record ID.
	 *
	 * @param values canonicalized row values
	 * @param recordNumber 1-based source record number excluding the header
	 * @param idColumn the column declared as the source's record identifier, possibly blank
	 * @return the record identifier
	 */
	private static String resolveRecordId(Map<String, String> values, long recordNumber, String idColumn) {
		String declared = idColumn == null ? null : nonEmpty(values, idColumn.trim());
		if (declared != null) {
			return declared;
		}
		String conventional = nonEmpty(values, "id");
		if (conventional != null) {
			return conventional;
		}
		String occurrenceId = nonEmpty(values, "occurrenceID");
		return occurrenceId != null ? occurrenceId : "row-" + recordNumber;
	}

	/**
	 * Reads a row value, treating a blank value as absent.
	 *
	 * @param values canonicalized row values
	 * @param column the column to read, possibly blank
	 * @return the value, or {@code null} if the column is absent or its value is blank
	 */
	private static String nonEmpty(Map<String, String> values, String column) {
		if (column == null || column.isEmpty()) {
			return null;
		}
		String value = values.get(column);
		return value == null || value.isBlank() ? null : value;
	}

	/**
	 * Trims a header or value, mapping {@code null} to {@code ""}.
	 *
	 * @param value the raw source value
	 * @return the normalized value
	 */
	private static String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	/**
	 * Searches an exception cause chain for an {@link IOException}.
	 *
	 * @param throwable the exception to inspect
	 * @return the first {@link IOException} cause found, or {@code null}
	 */
	private static IOException findIOException(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof IOException io) {
				return io;
			}
			current = current.getCause();
		}
		return null;
	}
}
