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
 * <p>Structured datasets expose a core record plus directly related tables. A field present on a
 * related table's rows is supplied by that relation; other fields come from the core record.
 * Relations then play one of two parts, decided from the data:
 *
 * <ul>
 *   <li>A <em>single-valued</em> relation has at most one row for every core record (typically a
 *       parent table, such as the event of an occurrence). It does not change the grain: its row
 *       is overlaid onto the core record, for fields the core record does not already carry. The
 *       exception is a binding that reads just one related table and nothing multi-valued; that
 *       table then governs, so its row-level subject references are kept.
 *   <li>A <em>multi-valued</em> relation has more than one row for some core record (such as the
 *       identifications of an occurrence). If a binding reads any of its fields, it governs the
 *       subject grain: the binding runs once per related row, each row overlaid onto the core
 *       record (and any single-valued rows), so core-record terms such as geography are reused by
 *       every per-row evaluation.
 * </ul>
 *
 * <p>When the core record itself carries every field a binding reads from the governing relation
 * (a view mapped the term from the grain as well as from the expanded table, e.g. the current
 * identification on an occurrence and the identification history in a related table), the core
 * record is evaluated too, as one more subject referencing the grain row, so both the grain's
 * value and each related row's value are tested and rolled up together.
 *
 * <p>Join-key columns (from the dataset's recorded relationships) never make a relation supply a
 * field: an identification row's {@code occurrenceID} links it to its occurrence and is not a
 * reason to evaluate a test reading {@code occurrenceID} once per identification.
 *
 * <p>A binding whose fields need two different multi-valued relations is rejected, since no single
 * row grain covers both. A core record with no rows in the governing relation is still evaluated,
 * once, at core grain, with that relation's fields blank: the record lacks those information
 * elements, which is itself a data quality finding the test reports (typically as internal
 * prerequisites not met), rather than an execution error.
 */
final class SubjectExpander {
	private final RecordDataset dataset;
	private final Map<String, Set<String>> relationsByField = new LinkedHashMap<>();
	private final Set<String> multiValuedRelations = new LinkedHashSet<>();

	/**
	 * Creates an expander over one execution dataset.
	 *
	 * @param dataset the execution dataset
	 */
	SubjectExpander(RecordDataset dataset) {
		this.dataset = dataset;
		Map<String, Set<String>> keyColumns = keyColumnsByTable(dataset);
		dataset.recordGraphs().forEach(graph -> graph.relatedByRelation().forEach((relation, records) -> {
			if (records.size() > 1) {
				multiValuedRelations.add(relation);
			}
			Set<String> keys = keyColumns.getOrDefault(relation.toLowerCase(), Set.of());
			records.forEach(record -> record.terms().keySet().stream()
					.filter(field -> !keys.contains(field))
					.forEach(field -> relationsByField.computeIfAbsent(field, ignored -> new LinkedHashSet<>())
							.add(relation)));
		}));
	}

	/**
	 * Collects the columns each table uses in a recorded relationship.
	 *
	 * @param dataset the execution dataset, whose input description records the relationships
	 * @return join-key columns keyed by lower-cased table name (which is also the relation name)
	 */
	private static Map<String, Set<String>> keyColumnsByTable(RecordDataset dataset) {
		Map<String, Set<String>> keys = new LinkedHashMap<>();
		dataset.inputDescription().relationships().forEach(relationship -> {
			keys.computeIfAbsent(relationship.fromTable().toLowerCase(), ignored -> new LinkedHashSet<>())
					.add(relationship.fromColumn());
			keys.computeIfAbsent(relationship.toTable().toLowerCase(), ignored -> new LinkedHashSet<>())
					.add(relationship.toColumn());
		});
		return keys;
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
		Governance governance = resolveGovernance(fields);
		if (governance.problem() != null) {
			List<ExpansionProblem> failures = dataset.recordGraphs().stream()
					.map(graph -> new ExpansionProblem(graph.core().id(), governance.problem()))
					.toList();
			return new SubjectExpansionResult(List.of(), failures);
		}
		List<EvaluationSubject> subjects = new ArrayList<>();
		for (RecordGraph graph : dataset.recordGraphs()) {
			expandGraph(graph, governance, subjects);
		}
		return new SubjectExpansionResult(subjects, List.of());
	}

	/**
	 * Decides which relations a binding's fields draw on, and which one (if any) governs the grain.
	 *
	 * @param fields the binding's fields
	 * @return the governance, or a problem when the fields need two multi-valued relations
	 */
	private Governance resolveGovernance(List<String> fields) {
		Set<String> governing = new LinkedHashSet<>();
		Set<String> singleValued = new LinkedHashSet<>();
		for (String field : fields) {
			for (String relation : relationsByField.getOrDefault(field, Set.of())) {
				if (multiValuedRelations.contains(relation)) {
					governing.add(relation);
				} else {
					singleValued.add(relation);
				}
			}
		}
		if (governing.size() > 1) {
			return new Governance(null, List.of(), List.of(),
					"Declared input fields span incomparable sibling relations: "
							+ String.join(", ", governing)
							+ "; a test's inputs can come from at most one related table with multiple rows "
							+ "per core record");
		}
		if (governing.isEmpty() && singleValued.size() == 1) {
			/* A lone related table keeps row-level subject references, as before. */
			governing.addAll(singleValued);
			singleValued.clear();
		}
		String relationName = governing.stream().findFirst().orElse(null);
		List<String> governedFields = relationName == null
				? List.of()
				: fields.stream()
						.filter(field -> relationsByField.getOrDefault(field, Set.of()).contains(relationName))
						.toList();
		return new Governance(relationName, List.copyOf(singleValued), governedFields, null);
	}

	/**
	 * Adds one graph's evaluation subjects: one per governing-relation row, or a single core-grain
	 * subject when there is no governing relation or it has no rows for this core record.
	 *
	 * @param graph the core record's graph
	 * @param governance the binding's governance
	 * @param subjects receives the graph's subjects
	 */
	private void expandGraph(RecordGraph graph, Governance governance, List<EvaluationSubject> subjects) {
		CanonicalRecord base = withSingleValuedRows(graph, governance.singleValuedRelations());
		if (governance.relationName() == null) {
			subjects.add(new EvaluationSubject(graph.core().id(), base, graph.core(), graph, null));
			return;
		}
		List<CanonicalRecord> governingRows = graph.relatedByRelation()
				.getOrDefault(governance.relationName(), List.of());
		if (governingRows.isEmpty()) {
			subjects.add(new EvaluationSubject(
					graph.core().id(),
					withBlankFields(base, governance.governedFields()),
					graph.core(),
					graph,
					null));
			return;
		}
		if (base.terms().keySet().containsAll(governance.governedFields())) {
			subjects.add(new EvaluationSubject(
					graph.core().id(),
					base,
					graph.core(),
					graph,
					subjectRef(graph.core().id(), "", graph.core())));
		}
		for (CanonicalRecord row : governingRows) {
			subjects.add(new EvaluationSubject(
					graph.core().id(),
					overlay(base, row),
					row,
					graph,
					subjectRef(graph.core().id(), governance.relationName(), row)));
		}
	}

	/**
	 * Overlays the rows of single-valued relations onto the core record, for fields the core record
	 * does not already carry, so an amendment written to the core record is not masked.
	 *
	 * @param graph the core record's graph
	 * @param relations the single-valued relations the binding reads
	 * @return the core record itself when there is nothing to overlay, otherwise an overlaid copy
	 */
	private CanonicalRecord withSingleValuedRows(RecordGraph graph, List<String> relations) {
		CanonicalRecord core = graph.core();
		Map<String, String> terms = null;
		Map<String, List<SourceCell>> provenance = null;
		for (String relation : relations) {
			List<CanonicalRecord> rows = graph.relatedByRelation().getOrDefault(relation, List.of());
			if (rows.isEmpty()) {
				continue;
			}
			if (terms == null) {
				terms = new LinkedHashMap<>(core.terms());
				provenance = new LinkedHashMap<>(core.provenanceByTerm());
			}
			CanonicalRecord row = rows.get(0);
			for (Map.Entry<String, String> term : row.terms().entrySet()) {
				if (terms.putIfAbsent(term.getKey(), term.getValue()) == null) {
					List<SourceCell> cells = row.provenanceByTerm().get(term.getKey());
					if (cells != null) {
						provenance.put(term.getKey(), cells);
					}
				}
			}
		}
		return terms == null ? core : new CanonicalRecord(core.id(), terms, provenance);
	}

	/**
	 * Copies a record, adding a blank value for each field it does not already carry.
	 *
	 * @param base the record to copy
	 * @param fields the fields the missing related rows would have supplied
	 * @return the record's terms with those fields present
	 */
	private CanonicalRecord withBlankFields(CanonicalRecord base, List<String> fields) {
		Map<String, String> terms = new LinkedHashMap<>(base.terms());
		fields.forEach(field -> terms.putIfAbsent(field, ""));
		return new CanonicalRecord(base.id(), terms, base.provenanceByTerm());
	}

	/**
	 * Overlays one governing row onto a base record; the row's values win.
	 *
	 * @param base the core record, with any single-valued rows overlaid
	 * @param governingRow the governing relation's row
	 * @return the subject's effective record
	 */
	private CanonicalRecord overlay(CanonicalRecord base, CanonicalRecord governingRow) {
		Map<String, String> terms = new LinkedHashMap<>(base.terms());
		terms.putAll(governingRow.terms());
		Map<String, List<SourceCell>> provenance = new LinkedHashMap<>(base.provenanceByTerm());
		governingRow.provenanceByTerm().forEach(provenance::put);
		return new CanonicalRecord(base.id(), terms, provenance);
	}

	/**
	 * Builds the subject reference for one evaluated row.
	 *
	 * @param coreRecordId the core record the subject belongs to
	 * @param governingRelation the governing relation, or {@code ""} for the core record itself
	 * @param row the evaluated row
	 * @return the subject reference, located by the row's own source cell
	 */
	private SubjectRef subjectRef(String coreRecordId, String governingRelation, CanonicalRecord row) {
		/* A flattened core record also holds cells from joined tables; prefer the row's own. */
		SourceCell cell = row.provenanceByTerm().values().stream()
				.flatMap(List::stream)
				.filter(candidate -> !governingRelation.isEmpty() || row.id().equals(candidate.rowRef()))
				.findFirst()
				.orElse(null);
		String relationName = governingRelation.isEmpty() && cell != null ? cell.table() : governingRelation;
		return new SubjectRef(
				coreRecordId,
				relationName,
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

	/**
	 * How one binding's fields map onto the dataset's relations.
	 *
	 * @param relationName the multi-valued relation governing the subject grain, or {@code null}
	 * @param singleValuedRelations single-valued relations whose row is overlaid onto the core
	 * @param governedFields the binding's fields supplied by the governing relation
	 * @param problem why the fields cannot be expanded, or {@code null}
	 */
	private record Governance(
			String relationName,
			List<String> singleValuedRelations,
			List<String> governedFields,
			String problem) {
	}
}
