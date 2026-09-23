/** SubjectExpander.java
 *
 * Expands structured record graphs into evaluation subjects at the correct governing grain.
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
package org.filteredpush.bdq_workbench.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.EvaluationSubject;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.SourceCell;
import org.filteredpush.bdq_workbench.model.SubjectRef;

/**
 * Expands one phase's dataset into evaluation subjects for one binding's declared input fields.
 *
 * <p>Structured datasets currently expose a core record plus directly related child tables.
 * Governing-grain selection is therefore explicit and deterministic: any field present on a child
 * relation is treated as deeper than the core record (depth 1 vs. 0), and a binding is rejected
 * if its declared fields require two different child relations of the same depth.
 */
final class SubjectExpander {
	private final RecordDataset dataset;
	private final Set<String> coreFields = new LinkedHashSet<>();
	private final Map<String, Set<String>> relationsByField = new LinkedHashMap<>();

	/**
	 * Creates an expander over one execution dataset.
	 *
	 * @param dataset the execution dataset
	 */
	SubjectExpander(RecordDataset dataset) {
		this.dataset = dataset;
		dataset.records().forEach(record -> coreFields.addAll(record.terms().keySet()));
		dataset.recordGraphs().forEach(graph -> graph.relatedByRelation().forEach((relation, records) ->
				records.forEach(record -> record.terms().keySet().forEach(field ->
						relationsByField.computeIfAbsent(field, ignored -> new LinkedHashSet<>()).add(relation)))));
	}

	/**
	 * Expands the dataset into evaluation subjects for {@code fields}.
	 *
	 * @param fields the canonical acted-upon/consulted field set for one binding
	 * @return the structured or flat evaluation subjects and any deterministic expansion failures
	 */
	SubjectExpansionResult expand(List<String> fields) {
		if (!dataset.hasStructuredGraphs()) {
			return new SubjectExpansionResult(
					dataset.records().stream().map(EvaluationSubject::flat).toList(),
					List.of());
		}
		GoverningRelation governing = resolveGovernance(fields);
		if (governing.problem() != null) {
			List<ExpansionProblem> failures = dataset.recordGraphs().stream()
					.map(graph -> new ExpansionProblem(graph.core().id(), governing.problem()))
					.toList();
			return new SubjectExpansionResult(List.of(), failures);
		}
		List<EvaluationSubject> subjects = new ArrayList<>();
		List<ExpansionProblem> failures = new ArrayList<>();
		for (RecordGraph graph : dataset.recordGraphs()) {
			expandGraph(graph, governing.relationName(), subjects, failures);
		}
		return new SubjectExpansionResult(subjects, failures);
	}

	private GoverningRelation resolveGovernance(List<String> fields) {
		Set<String> governingRelations = new LinkedHashSet<>();
		for (String field : fields) {
			Set<String> relationOwners = relationsByField.getOrDefault(field, Set.of());
			if (relationOwners.size() > 1) {
				return new GoverningRelation(
						null,
						"Field " + field + " is supplied by incomparable sibling relations: "
								+ String.join(", ", relationOwners));
			}
			relationOwners.stream().findFirst().ifPresent(governingRelations::add);
		}
		if (governingRelations.size() > 1) {
			return new GoverningRelation(
					null,
					"Declared input fields span incomparable sibling relations: "
							+ String.join(", ", governingRelations));
		}
		return new GoverningRelation(governingRelations.stream().findFirst().orElse(null), null);
	}

	private void expandGraph(
			RecordGraph graph,
			String governingRelation,
			List<EvaluationSubject> subjects,
			List<ExpansionProblem> failures) {
		if (governingRelation == null) {
			subjects.add(new EvaluationSubject(graph.core().id(), graph.core(), graph.core(), graph, null));
			return;
		}
		List<CanonicalRecord> governingRows = graph.relatedByRelation().getOrDefault(governingRelation, List.of());
		if (governingRows.isEmpty()) {
			failures.add(new ExpansionProblem(
					graph.core().id(),
					"No related rows were available for governing relation "
							+ governingRelation + " on core record " + graph.core().id()));
			return;
		}
		for (CanonicalRecord row : governingRows) {
			subjects.add(new EvaluationSubject(
					graph.core().id(),
					overlay(graph.core(), row),
					row,
					graph,
					subjectRef(graph.core().id(), governingRelation, row)));
		}
	}

	private CanonicalRecord overlay(CanonicalRecord core, CanonicalRecord governingRow) {
		Map<String, String> terms = new LinkedHashMap<>(core.terms());
		terms.putAll(governingRow.terms());
		Map<String, List<SourceCell>> provenance = new LinkedHashMap<>(core.provenanceByTerm());
		governingRow.provenanceByTerm().forEach(provenance::put);
		return new CanonicalRecord(core.id(), terms, provenance);
	}

	private SubjectRef subjectRef(String coreRecordId, String governingRelation, CanonicalRecord row) {
		SourceCell cell = row.provenanceByTerm().values().stream()
				.flatMap(List::stream)
				.findFirst()
				.orElse(null);
		return new SubjectRef(
				coreRecordId,
				governingRelation,
				cell == null ? governingRelation : cell.table(),
				cell == null ? governingRelation : cell.sourceLocation(),
				cell == null ? row.id() : cell.rowRef());
	}

	record SubjectExpansionResult(List<EvaluationSubject> subjects, List<ExpansionProblem> problems) {
		SubjectExpansionResult {
			subjects = List.copyOf(subjects);
			problems = List.copyOf(problems);
		}
	}

	record ExpansionProblem(String coreRecordId, String detail) {
	}

	private record GoverningRelation(String relationName, String problem) {
	}
}
