/** DataPackageDialectParser.java
 *
 * Parses a Darwin Core Data Package resource entry and its table dialect into a resource descriptor.
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses one Darwin Core Data Package resource entry into a {@link DataPackageResourceMeta}.
 *
 * <p>Reads the resource's {@code path} (a single file or a multipart list), {@code encoding},
 * table {@code schema} field names, and its table {@code dialect}, which may be given inline as
 * an object or by reference as the path of a sibling dialect JSON file. Dialect properties that
 * Commons CSV cannot honor when reading — {@code lineTerminator}, which Commons CSV instead
 * auto-detects — are ignored.
 *
 * <p>Anything the descriptor leaves unstated falls back to the Frictionless table dialect
 * defaults: comma delimiter, {@code "} quote character, doubled-quote escaping, and a header row.
 * Those are the same defaults the ingestor used before dialects were honored, so a data package
 * that declares nothing parses exactly as it did.
 */
final class DataPackageDialectParser {
	private static final Logger LOG = LoggerFactory.getLogger(DataPackageDialectParser.class);

	/** Frictionless table dialect default field delimiter. */
	private static final String DEFAULT_DELIMITER = ",";

	/** Frictionless table dialect default quote character. */
	private static final String DEFAULT_QUOTE_CHAR = "\"";

	/** Utility class; not instantiable. */
	private DataPackageDialectParser() {
	}

	/**
	 * Parses every tabular resource a data package manifest declares, as candidate core tables.
	 *
	 * <p>Resource order in a manifest carries no meaning, so every readable resource is offered
	 * and {@link CoreTableSelector} chooses between them. Resources with no readable data file —
	 * inline data, remote URLs, paths escaping the package directory — are skipped rather than
	 * failing the run, since a package may legitimately mix them with readable ones.
	 *
	 * @param mapper JSON mapper used to read a referenced dialect file
	 * @param root the parsed {@code datapackage.json} manifest
	 * @param packageDir the directory containing the manifest, which resource paths resolve against
	 * @return the readable resources in declaration order
	 */
	static List<CoreTableCandidate<DataPackageResourceMeta>> parseResources(ObjectMapper mapper, JsonNode root,
			Path packageDir) {
		List<CoreTableCandidate<DataPackageResourceMeta>> tables = new ArrayList<>();
		for (JsonNode resource : root.path("resources")) {
			DataPackageResourceMeta meta = parseResource(mapper, resource, packageDir);
			if (meta == null) {
				continue;
			}
			tables.add(new CoreTableCandidate<>(
					meta,
					meta.label(),
					identifyRowType(meta),
					rowTypeEvidence(meta),
					false,
					tables.size(),
					aliasesOf(meta)));
		}
		return tables;
	}

	/**
	 * Identifies a resource's row type, preferring its declared name, then its table schema
	 * reference, then its file name, and finally the terms its columns carry.
	 *
	 * @param meta the parsed resource descriptor
	 * @return the identified row type
	 */
	private static DatasetRowType identifyRowType(DataPackageResourceMeta meta) {
		for (String identifier : identifiersOf(meta)) {
			DatasetRowType rowType = DatasetRowType.fromIdentifier(identifier);
			if (rowType != DatasetRowType.OTHER) {
				return rowType;
			}
		}
		return DatasetRowType.fromColumnNames(meta.columnNames());
	}

	/**
	 * Describes which signal identified a resource's row type.
	 *
	 * @param meta the parsed resource descriptor
	 * @return a short description of the deciding signal
	 */
	private static String rowTypeEvidence(DataPackageResourceMeta meta) {
		String[] evidence = {"resource name", "table schema reference", "file name"};
		List<String> identifiers = identifiersOf(meta);
		for (int index = 0; index < identifiers.size(); index++) {
			if (DatasetRowType.fromIdentifier(identifiers.get(index)) != DatasetRowType.OTHER) {
				return evidence[index];
			}
		}
		return DatasetRowType.fromColumnNames(meta.columnNames()) != DatasetRowType.OTHER
				? "schema field names"
				: "unidentified";
	}

	/**
	 * Lists a resource's row-type identifiers, strongest signal first.
	 *
	 * @param meta the parsed resource descriptor
	 * @return the resource's name, table schema reference and first file name
	 */
	private static List<String> identifiersOf(DataPackageResourceMeta meta) {
		return List.of(meta.name(), meta.schemaReference(), meta.paths().get(0).getFileName().toString());
	}

	/**
	 * Lists the names by which a user may request a resource.
	 *
	 * @param meta the parsed resource descriptor
	 * @return the resource's name and its data file names
	 */
	private static List<String> aliasesOf(DataPackageResourceMeta meta) {
		List<String> aliases = new ArrayList<>();
		if (!meta.name().isBlank()) {
			aliases.add(meta.name());
		}
		meta.paths().forEach(path -> aliases.add(path.getFileName().toString()));
		return aliases;
	}

	/**
	 * Parses a resource entry from a data package manifest.
	 *
	 * @param mapper JSON mapper used to read a referenced dialect file
	 * @param resource the resource entry from the manifest's {@code resources} array
	 * @param packageDir the directory containing the manifest, which resource paths resolve against
	 * @return the resource descriptor, or {@code null} if the resource has no readable data file
	 */
	static DataPackageResourceMeta parseResource(ObjectMapper mapper, JsonNode resource, Path packageDir) {
		List<Path> paths = resolvePaths(resource.path("path"), packageDir);
		if (paths.isEmpty()) {
			LOG.debug("Skipping data package resource '{}', which declares no readable data file path"
					+ " (inline data and remote resource URLs are not supported)",
					resource.path("name").asText("unnamed"));
			return null;
		}
		JsonNode dialect = resolveDialect(mapper, resource.path("dialect"), packageDir);
		List<String> columnNames = readSchemaFieldNames(resource.path("schema"));
		DataPackageResourceMeta meta = new DataPackageResourceMeta(
				resource.path("name").asText(""),
				resolveSchemaReference(resource),
				paths,
				resolveEncoding(text(resource, "encoding")),
				requireNonEmpty(text(dialect, "delimiter"), DEFAULT_DELIMITER),
				toCharacter(textOrDefault(dialect, "quoteChar", DEFAULT_QUOTE_CHAR), "quoteChar"),
				resolveEscapeChar(dialect),
				toCharacter(text(dialect, "commentChar"), "commentChar"),
				text(dialect, "nullSequence"),
				dialect.path("skipInitialSpace").asBoolean(false),
				resolveHeaderLines(dialect),
				columnNames,
				readPrimaryKey(resource.path("schema")));
		LOG.debug("Parsed data package resource '{}': paths={}, encoding={}, delimiter={}, quoteChar={},"
						+ " escapeChar={}, headerLines={}, schemaColumns={}",
				meta.label(), meta.paths(), meta.encoding(), describe(meta.delimiter()), describe(meta.quoteChar()),
				describe(meta.escapeChar()), meta.headerLines(), meta.columnNames().size());
		return meta;
	}

	/**
	 * Reads a resource's table schema reference, where the schema is given by reference rather
	 * than inline.
	 *
	 * <p>Darwin Core Data Packages point their resources at per-table JSON Schemas published at
	 * stable URLs, whose names identify the table as reliably as the resource name does and
	 * survive a publisher renaming their files.
	 *
	 * @param resource the resource entry from the manifest
	 * @return the schema reference, or {@code ""} when the schema is inline or absent
	 */
	private static String resolveSchemaReference(JsonNode resource) {
		String schema = text(resource, "schema");
		if (schema != null) {
			return schema;
		}
		String profile = textOrDefault(resource, "$schema", text(resource, "profile"));
		return profile == null ? "" : profile;
	}

	/**
	 * Resolves a resource's {@code path} property, which may name one file or several parts.
	 *
	 * <p>Per the Frictionless specification a resource path is a relative POSIX path inside the
	 * package. Absolute paths, paths escaping the package directory, and remote URLs are rejected
	 * rather than resolved, since the manifest is untrusted input.
	 *
	 * @param pathNode the resource's {@code path} property
	 * @param packageDir the directory containing the manifest
	 * @return the resolved data file paths, in declaration order
	 */
	private static List<Path> resolvePaths(JsonNode pathNode, Path packageDir) {
		List<Path> paths = new ArrayList<>();
		if (pathNode.isArray()) {
			pathNode.forEach(part -> addResolvedPath(paths, part.asText(null), packageDir));
		} else if (pathNode.isTextual()) {
			addResolvedPath(paths, pathNode.asText(null), packageDir);
		}
		return paths;
	}

	/**
	 * Resolves one declared resource path against the package directory, rejecting unsafe values.
	 *
	 * @param paths collects the resolved path when it is usable
	 * @param declaredPath the path as declared in the manifest
	 * @param packageDir the directory containing the manifest
	 */
	private static void addResolvedPath(List<Path> paths, String declaredPath, Path packageDir) {
		if (declaredPath == null || declaredPath.isBlank()) {
			return;
		}
		if (declaredPath.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) {
			LOG.warn("Data package resource path '{}' is a remote URL, which is not supported", declaredPath);
			return;
		}
		Path base = packageDir.toAbsolutePath().normalize();
		Path resolved = base.resolve(declaredPath).normalize();
		if (!resolved.startsWith(base)) {
			LOG.warn("Data package resource path '{}' escapes the package directory and was ignored", declaredPath);
			return;
		}
		paths.add(resolved);
	}

	/**
	 * Resolves a resource's table dialect, which may be inline or a reference to a dialect file.
	 *
	 * @param mapper JSON mapper used to read a referenced dialect file
	 * @param dialectNode the resource's {@code dialect} property
	 * @param packageDir the directory containing the manifest
	 * @return the dialect object, or a missing node when the resource declares no usable dialect
	 */
	private static JsonNode resolveDialect(ObjectMapper mapper, JsonNode dialectNode, Path packageDir) {
		if (dialectNode.isObject()) {
			return dialectNode;
		}
		if (!dialectNode.isTextual()) {
			return dialectNode;
		}
		List<Path> dialectPaths = resolvePaths(dialectNode, packageDir);
		if (dialectPaths.isEmpty()) {
			return dialectNode;
		}
		Path dialectPath = dialectPaths.get(0);
		try {
			return mapper.readTree(Files.newBufferedReader(dialectPath, StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOG.warn("Could not read data package dialect file {} ({}); using table dialect defaults",
					dialectPath, e.toString());
			return dialectNode;
		}
	}

	/**
	 * Reads the single-column primary key a resource's table schema declares.
	 *
	 * <p>A composite primary key names no single column that could serve as a record identifier,
	 * so it is ignored rather than joined into one.
	 *
	 * @param schema the resource's {@code schema} property
	 * @return the primary key column name, or {@code ""} when there is no single-column key
	 */
	private static String readPrimaryKey(JsonNode schema) {
		JsonNode primaryKey = schema.path("primaryKey");
		if (primaryKey.isTextual()) {
			return primaryKey.asText();
		}
		if (primaryKey.isArray() && primaryKey.size() == 1 && primaryKey.get(0).isTextual()) {
			return primaryKey.get(0).asText();
		}
		return "";
	}

	/**
	 * Reads the column names declared by a resource's table schema.
	 *
	 * @param schema the resource's {@code schema} property
	 * @return the declared field names in column order, empty when the schema declares none
	 */
	private static List<String> readSchemaFieldNames(JsonNode schema) {
		List<String> names = new ArrayList<>();
		JsonNode fields = schema.path("fields");
		if (!fields.isArray()) {
			return names;
		}
		for (JsonNode field : fields) {
			String name = field.path("name").asText(null);
			if (name == null || name.isBlank()) {
				names.clear();
				LOG.warn("Data package table schema has an unnamed field; using the data file's header line instead");
				return names;
			}
			names.add(name.trim());
		}
		return names;
	}

	/**
	 * Determines how many leading header lines the dialect declares.
	 *
	 * <p>{@code header} defaults to true, meaning a single header line. {@code headerRows}, where
	 * present, gives the line numbers making up a multi-line header; only its size is used, since
	 * the workbench takes column names from the table schema or the first header line rather than
	 * joining several.
	 *
	 * @param dialect the resolved dialect object
	 * @return the number of leading lines making up the header
	 */
	private static int resolveHeaderLines(JsonNode dialect) {
		if (!dialect.path("header").asBoolean(true)) {
			return 0;
		}
		JsonNode headerRows = dialect.path("headerRows");
		if (headerRows.isArray() && !headerRows.isEmpty()) {
			if (headerRows.size() > 1) {
				LOG.warn("Data package dialect declares {} header rows; only the first supplies column names",
						headerRows.size());
			}
			return headerRows.size();
		}
		return 1;
	}

	/**
	 * Resolves the dialect's escape character.
	 *
	 * <p>A dialect either escapes quote characters by doubling them, which Commons CSV does by
	 * default, or with an explicit escape character. The two are mutually exclusive.
	 *
	 * @param dialect the resolved dialect object
	 * @return the escape character, or {@code null} when escaping is by doubled quotes
	 */
	private static Character resolveEscapeChar(JsonNode dialect) {
		Character escapeChar = toCharacter(text(dialect, "escapeChar"), "escapeChar");
		if (escapeChar != null && dialect.path("doubleQuote").asBoolean(false)) {
			LOG.warn("Data package dialect declares both escapeChar and doubleQuote; using escapeChar '{}'",
					escapeChar);
		}
		return escapeChar;
	}

	/**
	 * Resolves the declared character encoding, defaulting to UTF-8.
	 *
	 * @param encoding the declared encoding name, possibly null, blank or unsupported
	 * @return the resolved charset
	 */
	private static Charset resolveEncoding(String encoding) {
		if (encoding == null || encoding.isBlank()) {
			return StandardCharsets.UTF_8;
		}
		try {
			return Charset.forName(encoding.trim());
		} catch (RuntimeException e) {
			LOG.warn("Data package declares unsupported encoding '{}'; reading as UTF-8", encoding);
			return StandardCharsets.UTF_8;
		}
	}

	/**
	 * Converts a single-character dialect property to a character.
	 *
	 * <p>An empty declaration disables the property: {@code "quoteChar": ""} means fields are not
	 * quoted at all, so a bare {@code "} in the data is ordinary character data.
	 *
	 * @param value the declared property value
	 * @param propertyName the property name, for warning messages
	 * @return the character, or {@code null} when the property is absent or empty
	 */
	private static Character toCharacter(String value, String propertyName) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		if (value.length() > 1) {
			LOG.warn("Data package dialect declares multi-character {} '{}'; using its first character",
					propertyName, value);
		}
		return value.charAt(0);
	}

	/**
	 * Returns a textual JSON property value, or {@code null} when it is absent or not textual.
	 *
	 * @param node the containing object
	 * @param property the property name
	 * @return the property's text value, or {@code null}
	 */
	private static String text(JsonNode node, String property) {
		JsonNode value = node.path(property);
		return value.isTextual() ? value.asText() : null;
	}

	/**
	 * Returns a textual JSON property value, falling back to a default when it is absent.
	 *
	 * @param node the containing object
	 * @param property the property name
	 * @param defaultValue the value to use when the property is absent
	 * @return the property's text value, or {@code defaultValue}
	 */
	private static String textOrDefault(JsonNode node, String property, String defaultValue) {
		String value = text(node, property);
		return value == null ? defaultValue : value;
	}

	/**
	 * Returns a value, substituting a default when it is null or empty.
	 *
	 * @param value the declared value
	 * @param defaultValue the value to use when {@code value} is unusable
	 * @return the usable value
	 */
	private static String requireNonEmpty(String value, String defaultValue) {
		return value == null || value.isEmpty() ? defaultValue : value;
	}

	/**
	 * Renders a delimiter or special character for logging, with control characters made visible.
	 *
	 * @param value the value to render, possibly null
	 * @return a printable rendering of the value
	 */
	private static String describe(Object value) {
		if (value == null) {
			return "none";
		}
		return String.valueOf(value).replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
	}
}
