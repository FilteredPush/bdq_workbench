/** DatasetRowType.java
 *
 * The kind of Darwin Core row a dataset table holds, and the signals by which a table descriptor declares it.
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

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;

/**
 * The kind of Darwin Core row a dataset table holds.
 *
 * <p>A Darwin Core Archive declares this directly, as the {@code rowType} of its {@code <core>}
 * and each {@code <extension>}. A Darwin Core Data Package declares it less directly, through a
 * resource's name, its table schema reference, or its file name. Where a descriptor declares
 * nothing usable, the row type can still be inferred from the table's column names, since each
 * kind of row has terms that only it carries.
 *
 * <p>The declared order is a preference order: BDQ's ratified tests are predominantly
 * occurrence-level, so an occurrence table is the most useful thing to run a use case against,
 * a taxon table next, and so on. {@link CoreTableSelector} uses {@link #priority()} to rank the
 * tables a dataset offers.
 */
public enum DatasetRowType {

	/** An occurrence table; the most useful row type for the ratified BDQ tests. */
	OCCURRENCE(List.of("occurrence", "occurrences"),
			List.of("occurrenceID", "basisOfRecord", "occurrenceStatus", "catalogNumber", "recordNumber",
					"recordedBy"),
			"occurrenceID"),

	/** A taxon or checklist table. */
	TAXON(List.of("taxon", "taxa"),
			List.of("taxonID", "acceptedNameUsageID", "parentNameUsageID", "taxonomicStatus", "nomenclaturalCode",
					"scientificNameID"),
			"taxonID"),

	/** An event or sampling table. */
	EVENT(List.of("event", "events"),
			List.of("eventID", "parentEventID", "samplingProtocol", "sampleSizeValue", "samplingEffort"),
			"eventID"),

	/** Any other row type, including ones this workbench does not specifically recognize. */
	OTHER(List.of(), List.of(), "");

	/**
	 * Minimum number of discriminating terms a table must carry before its column names alone are
	 * taken as declaring a row type, so that one incidental term does not misclassify a table.
	 */
	private static final int MINIMUM_INFERENCE_MATCHES = 2;

	private final List<String> aliases;
	private final List<String> discriminatingTerms;
	private final String identifierTerm;

	/**
	 * Creates a row type.
	 *
	 * @param aliases names by which a descriptor may identify this row type
	 * @param discriminatingTerms Darwin Core terms characteristic of this row type
	 * @param identifierTerm the Darwin Core term conventionally identifying a row of this type
	 */
	DatasetRowType(List<String> aliases, List<String> discriminatingTerms, String identifierTerm) {
		this.aliases = aliases;
		this.discriminatingTerms = discriminatingTerms;
		this.identifierTerm = identifierTerm;
	}

	/**
	 * Returns the Darwin Core term conventionally identifying a row of this type.
	 *
	 * <p>Used as a fallback record identifier for tables whose descriptor declares no key of
	 * their own, so that a taxon or event table yields its rows' {@code taxonID}s or
	 * {@code eventID}s rather than synthesized positional identifiers.
	 *
	 * @return the identifying term's name, or {@code ""} for {@link #OTHER}
	 */
	public String identifierTerm() {
		return identifierTerm;
	}

	/**
	 * Returns this row type's selection priority, lowest first.
	 *
	 * @return the ordinal of this row type, which is its preference rank
	 */
	public int priority() {
		return ordinal();
	}

	/**
	 * Identifies a row type from a descriptor's identifier.
	 *
	 * <p>Accepts any of the forms descriptors use: a {@code rowType} IRI
	 * ({@code http://rs.tdwg.org/dwc/terms/Occurrence}), a Data Package resource name
	 * ({@code occurrence}), a table schema reference
	 * ({@code .../occurrence-table-schema.json}) or a file name ({@code occurrence.txt}). The
	 * identifier is reduced to its local name and split into words, so a decorated name still
	 * matches the row type it names.
	 *
	 * @param identifier the descriptor's identifier, possibly null or blank
	 * @return the identified row type, or {@link #OTHER} if the identifier names none
	 */
	public static DatasetRowType fromIdentifier(String identifier) {
		if (identifier == null || identifier.isBlank()) {
			return OTHER;
		}
		Set<String> words = words(DarwinCoreTermResolver.localName(identifier.trim()));
		return Arrays.stream(values())
				.filter(rowType -> rowType.aliases.stream().anyMatch(words::contains))
				.findFirst()
				.orElse(OTHER);
	}

	/**
	 * Infers a row type from a table's column names, for descriptors that identify their tables
	 * only by an opaque name.
	 *
	 * <p>Requires several characteristic terms before claiming a row type, and requires an
	 * outright winner, because tables routinely carry a few terms belonging to a related row
	 * type: an occurrence table commonly carries {@code taxonID}, for instance.
	 *
	 * @param columnNames the table's column names
	 * @return the inferred row type, or {@link #OTHER} if the columns are not characteristic
	 */
	public static DatasetRowType fromColumnNames(Collection<String> columnNames) {
		if (columnNames == null || columnNames.isEmpty()) {
			return OTHER;
		}
		Set<String> present = new LinkedHashSet<>();
		columnNames.forEach(column -> present.add(
				DarwinCoreTermResolver.normalizeTerm(DarwinCoreTermResolver.localName(column))));
		DatasetRowType best = OTHER;
		int bestMatches = 0;
		boolean tied = false;
		for (DatasetRowType rowType : values()) {
			int matches = (int) rowType.discriminatingTerms.stream()
					.map(DarwinCoreTermResolver::normalizeTerm)
					.filter(present::contains)
					.count();
			if (matches > bestMatches) {
				best = rowType;
				bestMatches = matches;
				tied = false;
			} else if (matches == bestMatches && matches > 0) {
				tied = true;
			}
		}
		return bestMatches >= MINIMUM_INFERENCE_MATCHES && !tied ? best : OTHER;
	}

	/**
	 * Reports whether an identifier names this specific row type.
	 *
	 * @param identifier the identifier to test
	 * @return {@code true} if the identifier names this row type
	 */
	public boolean matches(String identifier) {
		return this != OTHER && fromIdentifier(identifier) == this;
	}

	/**
	 * Splits an identifier into lower-case words, treating any non-letter as a separator.
	 *
	 * @param identifier the identifier to split
	 * @return the identifier's words
	 */
	private static Set<String> words(String identifier) {
		Set<String> words = new LinkedHashSet<>();
		for (String word : identifier.toLowerCase().split("[^a-z]+")) {
			if (!word.isEmpty()) {
				words.add(word);
			}
		}
		return words;
	}
}
