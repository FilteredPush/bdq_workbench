/** DwcArchiveMetaParser.java
 *
 * Parses the meta.xml descriptor of a Darwin Core Archive into a core data file descriptor.
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

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses the {@code meta.xml} descriptor of a Darwin Core Archive.
 *
 * <p>Reads the archive's {@code <core>} declaration into a {@link DwcArchiveCoreMeta}: the core
 * data file location(s), character encoding, field delimiter, field encapsulation character,
 * header line count, and the Darwin Core term carried by each column. Column names are the local
 * names of the declared term IRIs (for example {@code occurrenceID} for
 * {@code http://rs.tdwg.org/dwc/terms/occurrenceID}), matching the header names published
 * archives use and the names the rest of the pipeline resolves terms against.
 *
 * <p>Parsing is best-effort: a missing, malformed or unusable {@code meta.xml} yields an empty
 * result so the caller can fall back to its own defaults rather than failing the run. The XML
 * parser is configured to disallow doctype declarations and external entity resolution, since
 * archive contents are untrusted input.
 */
final class DwcArchiveMetaParser {
	private static final Logger LOG = LoggerFactory.getLogger(DwcArchiveMetaParser.class);

	/** Name of the descriptor entry defined by the Darwin Core text guide. */
	private static final String META_ENTRY_NAME = "meta.xml";

	/** Parser feature that rejects doctype declarations, and with them external entities. */
	private static final String DISALLOW_DOCTYPE_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl";

	/** Default field delimiter when {@code meta.xml} does not declare one. */
	private static final String DEFAULT_FIELDS_TERMINATED_BY = ",";

	/** Default field encapsulation character when {@code meta.xml} does not declare one. */
	private static final String DEFAULT_FIELDS_ENCLOSED_BY = "\"";

	/** Utility class; not instantiable. */
	private DwcArchiveMetaParser() {
	}

	/**
	 * Parses every table an archive's {@code meta.xml} declares: its {@code <core>} and each of
	 * its {@code <extension>}s.
	 *
	 * <p>The extensions are offered alongside the core because the declared core is not always
	 * the table a BDQ use case is about — a sample-based archive declares {@code Event} as its
	 * core and carries its occurrence data in an extension. {@link CoreTableSelector} decides
	 * which of them a run uses.
	 *
	 * @param zipFile the open Darwin Core Archive
	 * @return the declared tables in declaration order, core first; empty if the archive has no
	 *     usable {@code meta.xml}
	 */
	static List<CoreTableCandidate<DwcArchiveCoreMeta>> parseTables(ZipFile zipFile) {
		ZipEntry metaEntry = zipFile.getEntry(META_ENTRY_NAME);
		if (metaEntry == null) {
			LOG.debug("Archive has no {}; falling back to default core file conventions", META_ENTRY_NAME);
			return List.of();
		}
		try (InputStream metaStream = zipFile.getInputStream(metaEntry)) {
			Element archive = newDocumentBuilder().parse(metaStream).getDocumentElement();
			List<CoreTableCandidate<DwcArchiveCoreMeta>> tables = new ArrayList<>();
			addTable(tables, firstChildElement(archive, "core"), true);
			for (Element extension : childElements(archive, "extension")) {
				addTable(tables, extension, false);
			}
			if (tables.isEmpty()) {
				LOG.warn("Archive {} declares no readable <core> or <extension>; falling back to default core file"
						+ " conventions", META_ENTRY_NAME);
			}
			return tables;
		} catch (Exception e) {
			LOG.warn("Could not parse archive {} ({}); falling back to default core file conventions",
					META_ENTRY_NAME, e.toString());
			return List.of();
		}
	}

	/**
	 * Builds a candidate from one {@code <core>} or {@code <extension>} element and adds it to
	 * the offered tables, skipping elements that declare no usable data file.
	 *
	 * @param tables collects the candidate when the element is usable
	 * @param table the {@code <core>} or {@code <extension>} element, possibly null
	 * @param declaredCore whether this element is the archive's declared core
	 */
	private static void addTable(List<CoreTableCandidate<DwcArchiveCoreMeta>> tables, Element table,
			boolean declaredCore) {
		if (table == null) {
			return;
		}
		buildCoreMeta(table).ifPresent(meta -> tables.add(new CoreTableCandidate<>(
				meta,
				meta.locations().get(0),
				identifyRowType(meta),
				rowTypeEvidence(meta),
				declaredCore,
				tables.size(),
				List.copyOf(meta.locations()))));
	}

	/**
	 * Identifies a table's row type, preferring its declared {@code rowType} and falling back to
	 * its file name and then to the terms its columns carry.
	 *
	 * @param meta the parsed table descriptor
	 * @return the identified row type
	 */
	private static DatasetRowType identifyRowType(DwcArchiveCoreMeta meta) {
		DatasetRowType declared = DatasetRowType.fromIdentifier(meta.rowType());
		if (declared != DatasetRowType.OTHER) {
			return declared;
		}
		DatasetRowType byLocation = DatasetRowType.fromIdentifier(meta.locations().get(0));
		if (byLocation != DatasetRowType.OTHER) {
			return byLocation;
		}
		return DatasetRowType.fromColumnNames(meta.columnNames());
	}

	/**
	 * Describes which signal identified a table's row type.
	 *
	 * @param meta the parsed table descriptor
	 * @return a short description of the deciding signal
	 */
	private static String rowTypeEvidence(DwcArchiveCoreMeta meta) {
		if (DatasetRowType.fromIdentifier(meta.rowType()) != DatasetRowType.OTHER) {
			return "declared rowType " + meta.rowType();
		}
		if (DatasetRowType.fromIdentifier(meta.locations().get(0)) != DatasetRowType.OTHER) {
			return "file name";
		}
		if (DatasetRowType.fromColumnNames(meta.columnNames()) != DatasetRowType.OTHER) {
			return "declared terms";
		}
		return "unidentified";
	}

	/**
	 * Creates an XML document builder hardened against doctype and external entity attacks.
	 *
	 * @return a namespace-unaware document builder safe for untrusted input
	 * @throws Exception if the parser cannot be configured
	 */
	private static DocumentBuilder newDocumentBuilder() throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		factory.setFeature(DISALLOW_DOCTYPE_FEATURE, true);
		factory.setExpandEntityReferences(false);
		restrictExternalAccess(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
		restrictExternalAccess(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
		return factory.newDocumentBuilder();
	}

	/**
	 * Forbids one class of external reference, where the configured XML implementation supports
	 * the corresponding property.
	 *
	 * <p>Not every implementation on the classpath recognizes these JAXP properties on a document
	 * builder factory; they are defense in depth behind {@code disallow-doctype-decl}, which is
	 * set unconditionally and already blocks external entity resolution, so an unrecognized
	 * property is logged and ignored rather than failing the parse.
	 *
	 * @param factory the factory to restrict
	 * @param property the JAXP external access property to forbid
	 */
	private static void restrictExternalAccess(DocumentBuilderFactory factory, String property) {
		try {
			factory.setAttribute(property, "");
		} catch (IllegalArgumentException e) {
			LOG.debug("XML parser does not recognize {}; relying on {}", property, DISALLOW_DOCTYPE_FEATURE);
		}
	}

	/**
	 * Builds a table descriptor from a {@code <core>} or {@code <extension>} element.
	 *
	 * @param core the {@code <core>} or {@code <extension>} element from {@code meta.xml}
	 * @return the descriptor, or empty if the element declares no data file location
	 */
	private static Optional<DwcArchiveCoreMeta> buildCoreMeta(Element core) {
		List<String> locations = readLocations(core);
		if (locations.isEmpty()) {
			LOG.warn("Archive meta.xml <{}> declares no <location> and was ignored", stripPrefix(core.getNodeName()));
			return Optional.empty();
		}
		Map<Integer, String> namesByIndex = new TreeMap<>();
		Map<String, String> constantTerms = new LinkedHashMap<>();
		readFields(core, namesByIndex, constantTerms);
		int idIndex = parseInt(attribute(firstChildElement(core, "id"), "index"), -1);
		if (idIndex >= 0) {
			namesByIndex.putIfAbsent(idIndex, "id");
		}
		DwcArchiveCoreMeta meta = new DwcArchiveCoreMeta(
				attributeOrDefault(core, "rowType", ""),
				locations,
				resolveEncoding(attribute(core, "encoding")),
				decodeEscapes(attributeOrDefault(core, "fieldsTerminatedBy", DEFAULT_FIELDS_TERMINATED_BY)),
				resolveEnclosedBy(decodeEscapes(attributeOrDefault(core, "fieldsEnclosedBy", DEFAULT_FIELDS_ENCLOSED_BY))),
				Math.max(0, parseInt(attribute(core, "ignoreHeaderLines"), 0)),
				toColumnNames(namesByIndex),
				idIndex >= 0 ? namesByIndex.getOrDefault(idIndex, "") : "",
				constantTerms);
		LOG.debug("Parsed meta.xml table: rowType={}, locations={}, encoding={}, delimiter={}, enclosedBy={}, "
						+ "ignoreHeaderLines={}, columns={}, constantTerms={}",
				meta.rowType(), meta.locations(), meta.encoding(), describe(meta.fieldsTerminatedBy()),
				meta.fieldsEnclosedBy() == null ? "none" : describe(String.valueOf(meta.fieldsEnclosedBy())),
				meta.ignoreHeaderLines(), meta.columnNames().size(), meta.constantTerms().size());
		return Optional.of(meta);
	}

	/**
	 * Reads the {@code <files>/<location>} entries declared for the core.
	 *
	 * @param core the {@code <core>} element
	 * @return the declared core data file names, in document order
	 */
	private static List<String> readLocations(Element core) {
		List<String> locations = new ArrayList<>();
		Element files = firstChildElement(core, "files");
		if (files == null) {
			return locations;
		}
		for (Element location : childElements(files, "location")) {
			String value = location.getTextContent() == null ? "" : location.getTextContent().trim();
			if (!value.isEmpty()) {
				locations.add(value);
			}
		}
		return locations;
	}

	/**
	 * Reads the core's {@code <field>} declarations into indexed column names and constant terms.
	 *
	 * <p>A field with an {@code index} names a column in the data file. A field with only a
	 * {@code default} value declares a term that is constant for every record and absent from the
	 * data file.
	 *
	 * @param core the {@code <core>} element
	 * @param namesByIndex receives column names keyed by zero-based column index
	 * @param constantTerms receives term values that apply to every record
	 */
	private static void readFields(Element core, Map<Integer, String> namesByIndex, Map<String, String> constantTerms) {
		for (Element field : childElements(core, "field")) {
			String term = DarwinCoreTermResolver.localName(attribute(field, "term"));
			if (term.isBlank()) {
				continue;
			}
			int index = parseInt(attribute(field, "index"), -1);
			if (index >= 0) {
				namesByIndex.put(index, term);
			} else if (field.hasAttribute("default")) {
				constantTerms.put(term, attribute(field, "default"));
			}
		}
	}

	/**
	 * Expands indexed column names into a dense column name list.
	 *
	 * <p>Indices no {@code <field>} claims are given a positional placeholder name so Commons CSV
	 * still has a distinct name for every column up to the highest declared index.
	 *
	 * @param namesByIndex column names keyed by zero-based column index
	 * @return the dense list of column names
	 */
	private static List<String> toColumnNames(Map<Integer, String> namesByIndex) {
		List<String> columnNames = new ArrayList<>();
		if (namesByIndex.isEmpty()) {
			return columnNames;
		}
		int highestIndex = namesByIndex.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
		for (int index = 0; index <= highestIndex; index++) {
			columnNames.add(namesByIndex.getOrDefault(index, "column-" + index));
		}
		return columnNames;
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
			LOG.warn("Archive meta.xml declares unsupported encoding '{}'; reading as UTF-8", encoding);
			return StandardCharsets.UTF_8;
		}
	}

	/**
	 * Resolves the declared field encapsulation character.
	 *
	 * <p>An empty declaration means fields are not encapsulated at all, which is what most
	 * published archives declare; any {@code "} in the data is then ordinary character data.
	 *
	 * @param fieldsEnclosedBy the declared encapsulation string
	 * @return the encapsulation character, or {@code null} if fields are not encapsulated
	 */
	private static Character resolveEnclosedBy(String fieldsEnclosedBy) {
		if (fieldsEnclosedBy == null || fieldsEnclosedBy.isEmpty()) {
			return null;
		}
		if (fieldsEnclosedBy.length() > 1) {
			LOG.warn("Archive meta.xml declares multi-character fieldsEnclosedBy '{}'; using its first character",
					fieldsEnclosedBy);
		}
		return fieldsEnclosedBy.charAt(0);
	}

	/**
	 * Decodes the backslash escape sequences the Darwin Core text guide allows in delimiter
	 * attributes.
	 *
	 * @param value the raw attribute value
	 * @return the value with {@code \t}, {@code \n}, {@code \r} and {@code \\} expanded
	 */
	private static String decodeEscapes(String value) {
		if (value == null || value.indexOf('\\') < 0) {
			return value;
		}
		StringBuilder decoded = new StringBuilder(value.length());
		for (int position = 0; position < value.length(); position++) {
			char current = value.charAt(position);
			if (current != '\\' || position + 1 >= value.length()) {
				decoded.append(current);
				continue;
			}
			char escaped = value.charAt(++position);
			switch (escaped) {
				case 't' -> decoded.append('\t');
				case 'n' -> decoded.append('\n');
				case 'r' -> decoded.append('\r');
				case '\\' -> decoded.append('\\');
				default -> decoded.append('\\').append(escaped);
			}
		}
		return decoded.toString();
	}

	/**
	 * Renders a delimiter for logging with its control characters made visible.
	 *
	 * @param value the delimiter value
	 * @return a printable rendering of the value
	 */
	private static String describe(String value) {
		return value.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
	}

	/**
	 * Returns an element's attribute value, or {@code null} when the attribute is absent.
	 *
	 * @param element the element to inspect, possibly null
	 * @param name the attribute name
	 * @return the attribute value, or {@code null}
	 */
	private static String attribute(Element element, String name) {
		if (element == null || !element.hasAttribute(name)) {
			return null;
		}
		return element.getAttribute(name);
	}

	/**
	 * Returns an element's attribute value, falling back to a default when it is absent.
	 *
	 * @param element the element to inspect
	 * @param name the attribute name
	 * @param defaultValue the value to use when the attribute is absent
	 * @return the attribute value, or {@code defaultValue}
	 */
	private static String attributeOrDefault(Element element, String name, String defaultValue) {
		String value = attribute(element, name);
		return value == null ? defaultValue : value;
	}

	/**
	 * Parses an integer attribute value, falling back to a default when it is absent or malformed.
	 *
	 * @param value the raw attribute value
	 * @param defaultValue the value to use when parsing fails
	 * @return the parsed value, or {@code defaultValue}
	 */
	private static int parseInt(String value, int defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/**
	 * Finds the first child element with the given local name, ignoring namespace prefixes.
	 *
	 * @param parent the parent element, possibly null
	 * @param localName the local name to match
	 * @return the first matching child element, or {@code null}
	 */
	private static Element firstChildElement(Element parent, String localName) {
		List<Element> matches = childElements(parent, localName);
		return matches.isEmpty() ? null : matches.get(0);
	}

	/**
	 * Finds all child elements with the given local name, ignoring namespace prefixes.
	 *
	 * @param parent the parent element, possibly null
	 * @param localName the local name to match
	 * @return the matching child elements, in document order
	 */
	private static List<Element> childElements(Element parent, String localName) {
		List<Element> matches = new ArrayList<>();
		if (parent == null) {
			return matches;
		}
		NodeList children = parent.getChildNodes();
		for (int index = 0; index < children.getLength(); index++) {
			Node child = children.item(index);
			if (child instanceof Element element && localName.equals(stripPrefix(element.getNodeName()))) {
				matches.add(element);
			}
		}
		return matches;
	}

	/**
	 * Strips any namespace prefix from an XML node name.
	 *
	 * @param nodeName the node name, possibly prefixed
	 * @return the local part of the node name
	 */
	private static String stripPrefix(String nodeName) {
		int colon = nodeName.indexOf(':');
		return colon < 0 ? nodeName : nodeName.substring(colon + 1);
	}
}
