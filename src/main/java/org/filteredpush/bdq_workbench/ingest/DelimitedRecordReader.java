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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
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
	 * Reads a delimited source into canonical records.
	 *
	 * @param readerSupplier supplies a fresh reader for the source
	 * @param csvFormat primary Commons CSV format to use
	 * @return the parsed dataset
	 * @throws IOException if the source cannot be opened or read
	 */
	static RecordDataset read(ReaderSupplier readerSupplier, CSVFormat csvFormat) throws IOException {
		try {
			return readWithCommonsCsv(readerSupplier, csvFormat);
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
	 * @return the parsed dataset
	 * @throws IOException if the source cannot be opened or parsed
	 */
	private static RecordDataset readWithCommonsCsv(ReaderSupplier readerSupplier, CSVFormat csvFormat) throws IOException {
		try (BufferedReader reader = readerSupplier.open();
				CSVParser parser = csvFormat.parse(reader)) {
			List<CanonicalRecord> records = new ArrayList<>();
			parser.forEach(row -> {
				Map<String, String> values = new LinkedHashMap<>();
				row.toMap().forEach((key, value) -> values.put(normalize(key), normalize(value)));
				records.add(new CanonicalRecord(resolveRecordId(values, row.getRecordNumber()), values));
			});
			return new RecordDataset(records);
		}
	}

	/**
	 * Resolves the record ID using the standard workbench precedence.
	 *
	 * @param values canonicalized row values
	 * @param recordNumber 1-based source record number excluding the header
	 * @return the record identifier
	 */
	private static String resolveRecordId(Map<String, String> values, long recordNumber) {
		return values.getOrDefault("id", values.getOrDefault("occurrenceID", "row-" + recordNumber));
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
