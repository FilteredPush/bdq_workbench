package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.SourceCell;
import org.junit.jupiter.api.Test;

class SubjectExpanderTest {

	@Test
	void flatDatasetExpandsToOneCoreSubjectPerRecord() {
		SubjectExpander expander = new SubjectExpander(new RecordDataset(List.of(
				new CanonicalRecord("r1", Map.of("occurrenceID", "r1", "eventDate", "2020-01-01")),
				new CanonicalRecord("r2", Map.of("occurrenceID", "r2", "eventDate", "2020-01-02")))));

		SubjectExpander.SubjectExpansionResult expansion = expander.expand(List.of("eventDate"));

		assertThat(expansion.problems()).isEmpty();
		assertThat(expansion.subjects()).hasSize(2);
		assertThat(expansion.subjects()).allMatch(subject -> !subject.hasStructuredReference());
	}

	@Test
	void relationFieldExpandsToOneSubjectPerRelatedRow() {
		SubjectExpander expander = new SubjectExpander(new RecordDataset(
				List.of(core("occ-1", Map.of("occurrenceID", "occ-1", "eventDate", "2020-01-01"))),
				List.of(new RecordGraph(
						core("occ-1", Map.of("occurrenceID", "occ-1", "eventDate", "2020-01-01")),
						Map.of("identification", List.of(
								related("id-1", "identification", Map.of("scientificName", "Aus bus")),
								related("id-2", "identification", Map.of("scientificName", "Cus dus"))))))));

		SubjectExpander.SubjectExpansionResult expansion = expander.expand(List.of("scientificName"));

		assertThat(expansion.problems()).isEmpty();
		assertThat(expansion.subjects()).hasSize(2);
		assertThat(expansion.subjects()).extracting(subject -> subject.effectiveRecord().terms().get("scientificName"))
				.containsExactly("Aus bus", "Cus dus");
	}

	@Test
	void governingRelationBroadcastsInheritedCoreTerms() {
		CanonicalRecord core = core("occ-1", Map.of("occurrenceID", "occ-1", "eventDate", "2020-01-01"));
		SubjectExpander expander = new SubjectExpander(new RecordDataset(
				List.of(core),
				List.of(new RecordGraph(
						core,
						Map.of("identification", List.of(
								related("id-1", "identification", Map.of("scientificName", "Aus bus"))))))));

		SubjectExpander.SubjectExpansionResult expansion = expander.expand(List.of("eventDate", "scientificName"));

		assertThat(expansion.problems()).isEmpty();
		assertThat(expansion.subjects()).singleElement().satisfies(subject -> {
			assertThat(subject.hasStructuredReference()).isTrue();
			assertThat(subject.effectiveRecord().terms())
					.containsEntry("eventDate", "2020-01-01")
					.containsEntry("scientificName", "Aus bus");
		});
	}

	@Test
	void siblingRelationsProduceDeterministicDiagnostic() {
		CanonicalRecord core = core("occ-1", Map.of("occurrenceID", "occ-1"));
		SubjectExpander expander = new SubjectExpander(new RecordDataset(
				List.of(core),
				List.of(new RecordGraph(
						core,
						Map.of(
								"identification", List.of(related("id-1", "identification", Map.of("scientificName", "Aus bus"))),
								"measurement", List.of(related("m-1", "measurement", Map.of("measurementValue", "x"))))))));

		SubjectExpander.SubjectExpansionResult expansion = expander.expand(List.of("scientificName", "measurementValue"));

		assertThat(expansion.subjects()).isEmpty();
		assertThat(expansion.problems()).singleElement()
				.extracting(SubjectExpander.ExpansionProblem::detail)
				.asString()
				.contains("incomparable sibling relations");
	}

	private static CanonicalRecord core(String id, Map<String, String> terms) {
		return new CanonicalRecord(id, terms, provenance("occurrence", id, terms.keySet()));
	}

	private static CanonicalRecord related(String id, String table, Map<String, String> terms) {
		return new CanonicalRecord(id, terms, provenance(table, id, terms.keySet()));
	}

	private static Map<String, List<SourceCell>> provenance(
			String table,
			String rowRef,
			java.util.Set<String> terms) {
		Map<String, List<SourceCell>> provenance = new java.util.LinkedHashMap<>();
		for (String term : terms) {
			provenance.put(term, List.of(new SourceCell(table, table, rowRef, term, term)));
		}
		return provenance;
	}
}
