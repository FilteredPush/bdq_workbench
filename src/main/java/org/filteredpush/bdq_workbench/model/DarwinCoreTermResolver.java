/** DarwinCoreTermResolver.java
 *
 * Shared Darwin Core term alias resolution helpers used by binding and record filtering.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Resolves Darwin Core term aliases against the actual term names present in a dataset.
 *
 * <p>Terms are matched case-insensitively by their full name and by local name, so callers can
 * accept user or annotation input such as {@code dwc:country}, {@code country}, or a full IRI and
 * map it back to the exact key present in {@link CanonicalRecord#terms()}.
 */
public final class DarwinCoreTermResolver {

	/** Utility class; not instantiable. */
	private DarwinCoreTermResolver() {
	}

	/**
	 * Builds an alias index over the available dataset terms.
	 *
	 * <p>Each term is indexed twice: by its full normalized value and by its normalized local name.
	 * Terms are processed in sorted order so that {@link Resolution#preferredMatch()} is stable.
	 *
	 * @param availableTerms the Darwin Core terms present in the dataset
	 * @return aliases mapped to one or more actual dataset term names
	 */
	public static Map<String, List<String>> indexAvailableTerms(Collection<String> availableTerms) {
		Map<String, LinkedHashSet<String>> byAlias = new LinkedHashMap<>();
		availableTerms.stream().sorted().forEach(term -> {
			addAlias(byAlias, normalizeTerm(term), term);
			addAlias(byAlias, normalizeTerm(localName(term)), term);
		});
		Map<String, List<String>> indexed = new LinkedHashMap<>();
		byAlias.forEach((alias, terms) -> indexed.put(alias, List.copyOf(terms)));
		return Collections.unmodifiableMap(indexed);
	}

	/**
	 * Resolves a requested term name against the given alias index.
	 *
	 * <p>The lookup first tries the requested value itself, then its local name form.
	 *
	 * @param requested the requested field name
	 * @param availableTermsByAlias alias index built by {@link #indexAvailableTerms(Collection)}
	 * @return the resolution result, possibly empty or ambiguous
	 */
	public static Resolution resolve(String requested, Map<String, List<String>> availableTermsByAlias) {
		if (requested == null || requested.isBlank()) {
			return new Resolution(requested, List.of());
		}
		List<String> exact = availableTermsByAlias.get(normalizeTerm(requested));
		if (exact != null && !exact.isEmpty()) {
			return new Resolution(requested, exact);
		}
		List<String> local = availableTermsByAlias.get(normalizeTerm(localName(requested)));
		return new Resolution(requested, local == null ? List.of() : local);
	}

	/**
	 * Extracts the local (unqualified) name from a term identifier.
	 *
	 * @param value the term identifier, typically a CURIE or IRI
	 * @return the local name portion, or {@code value} itself if no separator exists
	 */
	public static String localName(String value) {
		if (value == null) {
			return "";
		}
		int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('#'));
		int colon = value.lastIndexOf(':');
		int index = Math.max(slash, colon);
		return index >= 0 && index + 1 < value.length() ? value.substring(index + 1) : value;
	}

	/**
	 * Normalizes a term name for alias comparison by trimming and lower-casing it.
	 *
	 * @param value the raw term name
	 * @return the normalized term name, or {@code ""} for null
	 */
	public static String normalizeTerm(String value) {
		return value == null ? "" : value.trim().toLowerCase();
	}

	/**
	 * Outcome of resolving one requested field name against the dataset's available terms.
	 *
	 * @param requested the requested field name
	 * @param matches every actual dataset term that matches the requested alias
	 */
	public record Resolution(String requested, List<String> matches) {

		/**
		 * Canonical constructor; copies {@code matches} defensively.
		 */
		public Resolution {
			matches = List.copyOf(matches == null ? List.of() : new ArrayList<>(matches));
		}

		/**
		 * @return {@code true} if exactly one matching dataset term was found
		 */
		public boolean isUnique() {
			return matches.size() == 1;
		}

		/**
		 * @return {@code true} if more than one dataset term matched the requested alias
		 */
		public boolean isAmbiguous() {
			return matches.size() > 1;
		}

		/**
		 * @return the first matching term in stable sorted order, or {@code null} if none matched
		 */
		public String preferredMatch() {
			return matches.isEmpty() ? null : matches.get(0);
		}
	}

	/**
	 * Adds one actual term to an alias bucket, preserving first occurrence.
	 *
	 * @param byAlias alias buckets
	 * @param alias the normalized alias
	 * @param term the actual dataset term
	 */
	private static void addAlias(Map<String, LinkedHashSet<String>> byAlias, String alias, String term) {
		byAlias.computeIfAbsent(alias, key -> new LinkedHashSet<>()).add(term);
	}
}
