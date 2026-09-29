/** SourceRow.java
 *
 * The file and line an input record was read from, for identifying records to people.
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
package org.filteredpush.bdq_workbench.model;

/**
 * Where an input record came from: a data file inside the Darwin Core Archive or data package,
 * and the 1-based line of that file on which the record starts (header lines included, so it is
 * the line a person sees when opening the file).
 *
 * @param file the data file's path within the archive or package, e.g. {@code occurrence.txt}
 * @param line the 1-based line the record starts on; {@code 0} when unknown
 */
public record SourceRow(String file, long line) {

	private static final SourceRow UNKNOWN = new SourceRow("", 0);

	/**
	 * Canonical constructor; substitutes {@code ""} for a null file.
	 */
	public SourceRow {
		file = file == null ? "" : file;
	}

	/**
	 * @return the position used when a record's source is unknown
	 */
	public static SourceRow unknown() {
		return UNKNOWN;
	}

	/**
	 * @return {@code true} when the line is known
	 */
	public boolean known() {
		return line > 0;
	}

	/**
	 * @return e.g. {@code "occurrence.txt line 57"}, or {@code ""} when unknown
	 */
	public String label() {
		if (!known()) {
			return "";
		}
		return file.isBlank() ? "line " + line : file + " line " + line;
	}
}
