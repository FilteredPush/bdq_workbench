/** DwcArchiveCoreMeta.java
 *
 * Descriptor of a Darwin Core Archive's core data file as declared by the archive's meta.xml.
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

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Descriptor of a Darwin Core Archive's core data file, as declared by the archive's
 * {@code meta.xml}.
 *
 * <p>The Darwin Core text guide lets an archive choose its own field delimiter, field
 * encapsulation character, character encoding and header-line count, and declares the Darwin Core
 * term each column carries. Honoring those declarations rather than assuming a fixed
 * tab-separated, double-quote-encapsulated layout matters in practice: most published archives
 * (GBIF downloads among them) declare {@code fieldsEnclosedBy=""}, meaning no encapsulation at
 * all, and their data routinely contains bare {@code "} characters (unit marks, nicknames,
 * quoted verbatim text). Parsing such a file as if {@code "} encapsulated fields either fails
 * partway through the file or silently merges records together from the first offending row on.
 *
 * @param rowType the {@code rowType} IRI the archive declares for this table, or {@code ""}
 * @param locations core data file names within the archive, in the order they should be read
 * @param encoding character encoding of the core data files
 * @param fieldsTerminatedBy the field delimiter
 * @param fieldsEnclosedBy the field encapsulation character, or {@code null} when fields are not
 *     encapsulated
 * @param ignoreHeaderLines number of leading lines to skip in each core data file
 * @param columnNames column names by zero-based column index, with gaps filled by placeholders
 * @param idColumn the name of the column the archive declares as this table's record
 *     identifier, or {@code ""} when it declares none
 * @param coreIdColumn the name of the extension column declaring the related core record
 *     identifier, or {@code ""} when this table is a core or declares none
 * @param constantTerms term values declared in {@code meta.xml} as defaults for columns that are
 *     absent from the data files, applied to every record
 */
public record DwcArchiveCoreMeta(
		String rowType,
		List<String> locations,
		Charset encoding,
		String fieldsTerminatedBy,
		Character fieldsEnclosedBy,
		int ignoreHeaderLines,
		List<String> columnNames,
		String idColumn,
		String coreIdColumn,
		Map<String, String> constantTerms) {

	/**
	 * Canonical constructor; copies the collection components defensively.
	 */
	public DwcArchiveCoreMeta {
		rowType = rowType == null ? "" : rowType;
		locations = List.copyOf(locations);
		columnNames = List.copyOf(columnNames);
		idColumn = idColumn == null ? "" : idColumn;
		coreIdColumn = coreIdColumn == null ? "" : coreIdColumn;
		constantTerms = Map.copyOf(new LinkedHashMap<>(constantTerms));
	}
}
