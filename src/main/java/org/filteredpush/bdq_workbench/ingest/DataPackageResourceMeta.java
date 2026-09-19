/** DataPackageResourceMeta.java
 *
 * Descriptor of a Darwin Core Data Package tabular resource, as declared by its datapackage.json entry and table dialect.
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
import java.nio.file.Path;
import java.util.List;

/**
 * Descriptor of one tabular resource in a Darwin Core Data Package.
 *
 * <p>Frictionless data packages declare their own table dialect — delimiter, quote character,
 * escaping, header layout, comment marker and null sequence — alongside the resource's character
 * encoding and, in its table schema, the name of each column. This is the Data Package analogue
 * of a Darwin Core Archive's {@code meta.xml} core declaration (see {@link DwcArchiveCoreMeta}),
 * and it matters for the same reason: a resource that declares {@code "quoteChar": ""} or a
 * semicolon delimiter parses to garbage, or fails partway through the file, if it is read as
 * default comma-separated, double-quote-encapsulated CSV.
 *
 * @param name the resource's declared name, or {@code ""} when it declares none
 * @param schemaReference the resource's declared table schema reference, or {@code ""}
 * @param paths the resource's data files, in the order they should be read
 * @param encoding character encoding of the data files
 * @param delimiter the field delimiter
 * @param quoteChar the field quote character, or {@code null} when fields are not quoted
 * @param escapeChar the escape character, or {@code null} when escaping is by doubled quotes
 * @param commentChar the comment line marker, or {@code null} when the dialect declares none
 * @param nullSequence the sequence denoting a null value, or {@code null} when none is declared
 * @param skipInitialSpace whether spaces adjacent to the delimiter should be ignored
 * @param headerLines the number of leading header lines, zero when the resource has no header
 * @param columnNames column names from the resource's table schema, empty when it declares none
 * @param idColumn the single-column primary key the resource's table schema declares, or
 *     {@code ""} when it declares none or declares a composite one
 */
public record DataPackageResourceMeta(
		String name,
		String schemaReference,
		List<Path> paths,
		Charset encoding,
		String delimiter,
		Character quoteChar,
		Character escapeChar,
		Character commentChar,
		String nullSequence,
		boolean skipInitialSpace,
		int headerLines,
		List<String> columnNames,
		String idColumn) {

	/**
	 * Canonical constructor; copies the collection components defensively.
	 */
	public DataPackageResourceMeta {
		name = name == null ? "" : name;
		schemaReference = schemaReference == null ? "" : schemaReference;
		paths = List.copyOf(paths);
		columnNames = List.copyOf(columnNames);
		idColumn = idColumn == null ? "" : idColumn;
	}

	/**
	 * Returns a short human-readable name for this resource.
	 *
	 * @return the declared resource name, or the first data file's name when it declares none
	 */
	public String label() {
		return name.isBlank() ? paths.get(0).getFileName().toString() : name;
	}

	/**
	 * Reports whether the resource's own first line supplies its column names.
	 *
	 * <p>When the table schema names the columns they are authoritative, so the header line, if
	 * any, is skipped as data rather than read as names.
	 *
	 * @return {@code true} if column names should be taken from the data file's header line
	 */
	public boolean namesColumnsFromHeaderLine() {
		return columnNames.isEmpty() && headerLines > 0;
	}
}
