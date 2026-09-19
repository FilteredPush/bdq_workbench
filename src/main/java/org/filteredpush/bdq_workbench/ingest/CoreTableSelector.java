/** CoreTableSelector.java
 *
 * Chooses which of a dataset's tables a run should be executed against.
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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses which of a dataset's tables a run should be executed against.
 *
 * <p>Datasets routinely offer more than one table, and the one a descriptor puts first is not
 * necessarily the one a BDQ use case is about. A sample-based Darwin Core Archive declares
 * {@code Event} as its core and carries its occurrence data in an extension; a Darwin Core Data
 * Package lists its tables in no meaningful order. Running a predominantly occurrence-level use
 * case against event rows or against whichever resource happens to be listed first produces a
 * report that is almost entirely {@code INTERNAL_PREREQUISITES_NOT_MET}, with nothing to say
 * why.
 *
 * <p>Selection ranks the offered tables by {@link DatasetRowType#priority()}, preferring a
 * table the dataset declares as its core where two rank equally, and falling back to declaration
 * order. A caller-supplied table name overrides the ranking entirely, so a user whose dataset
 * the ranking gets wrong is not stuck with it.
 *
 * <p>This is deliberately a choice of one table read on its own. It does not join a chosen
 * extension back to its core, or a Data Package resource to the resources it references; tests
 * whose information elements are spread across related tables still see only the terms the
 * selected table carries.
 */
final class CoreTableSelector {
	private static final Logger LOG = LoggerFactory.getLogger(CoreTableSelector.class);

	/** Utility class; not instantiable. */
	private CoreTableSelector() {
	}

	/**
	 * The outcome of choosing a table, including the alternatives that were passed over.
	 *
	 * @param <T> the format-specific table descriptor type
	 * @param selected the chosen table
	 * @param ranked every candidate, best first
	 * @param reason a short description of why {@code selected} was chosen
	 */
	record Selection<T>(CoreTableCandidate<T> selected, List<CoreTableCandidate<T>> ranked, String reason) {

		/**
		 * Canonical constructor; copies {@code ranked} defensively.
		 */
		Selection {
			ranked = List.copyOf(ranked);
		}
	}

	/**
	 * Chooses one of the tables a dataset offers.
	 *
	 * @param <T> the format-specific table descriptor type
	 * @param candidates the tables the dataset offers, in declaration order; must not be empty
	 * @param requestedTable a table name or row type the caller asked for, blank for automatic
	 *     selection
	 * @return the selection
	 * @throws IllegalArgumentException if {@code candidates} is empty
	 */
	static <T> Selection<T> select(List<CoreTableCandidate<T>> candidates, String requestedTable) {
		if (candidates.isEmpty()) {
			throw new IllegalArgumentException("Cannot select a core table from an empty candidate list");
		}
		List<CoreTableCandidate<T>> ranked = candidates.stream().sorted(byPreference()).toList();
		Selection<T> selection = findRequested(ranked, requestedTable)
				.map(requested -> new Selection<>(requested, ranked,
						"requested by name '" + requestedTable.trim() + "'"))
				.orElseGet(() -> new Selection<>(ranked.get(0), ranked, automaticReason(ranked.get(0))));
		logSelection(selection, candidates.size());
		return selection;
	}

	/**
	 * Orders candidates by how useful they are to run a BDQ use case against.
	 *
	 * @param <T> the format-specific table descriptor type
	 * @return the preference comparator, best first
	 */
	private static <T> Comparator<CoreTableCandidate<T>> byPreference() {
		return Comparator
				.<CoreTableCandidate<T>>comparingInt(candidate -> candidate.rowType().priority())
				.thenComparing(candidate -> !candidate.declaredCore())
				.thenComparingInt(CoreTableCandidate::declarationOrder);
	}

	/**
	 * Finds the candidate a caller explicitly asked for.
	 *
	 * <p>A request matches a candidate's label, any of its aliases, or its row type name, so a
	 * user can name the table either by how the descriptor calls it ({@code occurrence.txt},
	 * {@code occurrence}) or by what it holds ({@code Occurrence}).
	 *
	 * @param <T> the format-specific table descriptor type
	 * @param ranked the candidates, best first
	 * @param requestedTable the requested table name, possibly blank
	 * @return the requested candidate, or empty for automatic selection
	 */
	private static <T> Optional<CoreTableCandidate<T>> findRequested(List<CoreTableCandidate<T>> ranked,
			String requestedTable) {
		if (requestedTable == null || requestedTable.isBlank()) {
			return Optional.empty();
		}
		String requested = normalize(requestedTable);
		Optional<CoreTableCandidate<T>> match = ranked.stream()
				.filter(candidate -> matchesRequest(candidate, requested))
				.findFirst();
		if (match.isEmpty()) {
			LOG.warn("Requested core table '{}' matches none of the dataset's tables {}; selecting automatically",
					requestedTable.trim(), ranked.stream().map(CoreTableCandidate::label).toList());
		}
		return match;
	}

	/**
	 * Reports whether a candidate answers to a requested name.
	 *
	 * @param <T> the format-specific table descriptor type
	 * @param candidate the candidate to test
	 * @param requested the normalized requested name
	 * @return {@code true} if the candidate answers to the requested name
	 */
	private static <T> boolean matchesRequest(CoreTableCandidate<T> candidate, String requested) {
		if (normalize(candidate.label()).equals(requested)
				|| normalize(DarwinCoreTermResolver.localName(candidate.label())).equals(requested)) {
			return true;
		}
		if (candidate.aliases().stream().anyMatch(alias -> normalize(alias).equals(requested))) {
			return true;
		}
		return candidate.rowType() != DatasetRowType.OTHER
				&& candidate.rowType().name().toLowerCase(Locale.ROOT).equals(requested);
	}

	/**
	 * Describes why a table was chosen automatically.
	 *
	 * @param <T> the format-specific table descriptor type
	 * @param selected the chosen table
	 * @return a short reason
	 */
	private static <T> String automaticReason(CoreTableCandidate<T> selected) {
		if (selected.rowType() != DatasetRowType.OTHER) {
			return "highest-ranked row type " + selected.rowType() + " (" + selected.rowTypeEvidence() + ")";
		}
		return selected.declaredCore() ? "declared core table" : "first table declared";
	}

	/**
	 * Logs the selection, listing the alternatives whenever there was a real choice to make.
	 *
	 * @param <T> the format-specific table descriptor type
	 * @param selection the selection made
	 * @param candidateCount the number of tables the dataset offered
	 */
	private static <T> void logSelection(Selection<T> selection, int candidateCount) {
		if (candidateCount == 1) {
			LOG.debug("Dataset offers one table, {}", selection.selected().describe());
			return;
		}
		LOG.info("Selected core table {} ({}); passed over {}",
				selection.selected().describe(),
				selection.reason(),
				selection.ranked().stream()
						.filter(candidate -> candidate != selection.selected())
						.map(CoreTableCandidate::describe)
						.toList());
	}

	/**
	 * Normalizes a table name for comparison.
	 *
	 * @param value the raw name
	 * @return the trimmed, lower-cased name
	 */
	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
