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
								"identification", List.of(
										related("id-1", "identification", Map.of("scientificName", "Aus bus")),
										related("id-2", "identification", Map.of("scientificName", "Cus dus"))),
								"measurement", List.of(
										related("m-1", "measurement", Map.of("measurementValue", "x")),
										related("m-2", "measurement", Map.of("measurementValue", "y"))))))));

		SubjectExpander.SubjectExpansionResult expansion = expander.expand(List.of("scientificName", "measurementValue"));

		assertThat(expansion.subjects()).isEmpty();
		assertThat(expansion.problems()).singleElement()
				.extracting(SubjectExpander.ExpansionProblem::detail)
				.asString()
				.contains("incomparable sibling relations");
	}

	@Test
	void multiValuedRelationGovernsWhileSingleValuedParentIsOverlaid() {
		CanonicalRecord core = core("occ-1", Map.of("occurrenceID", "occ-1"));
		CanonicalRecord event = related("ev-1", "event", Map.of("eventDate", "2020-06-01",
				"decimalLatitude", "10.5", "decimalLongitude", "-60.1"));
		SubjectExpander expander = new SubjectExpander(new RecordDataset(
				List.of(core),
				List.of(new RecordGraph(
						core,
						Map.of(
								"event", List.of(event),
								"identification", List.of(
										related("id-1", "identification", Map.of("dateIdentified", "2021-01-01",
												"scientificName", "Aus bus")),
										related("id-2", "identification", Map.of("dateIdentified", "1999-01-01",
												"scientificName", "Cus dus"))))))));

		/* Shapes of VALIDATION_DATEIDENTIFIED_INRANGE and VALIDATION_COORDINATESTERRESTRIALMARINE_CONSISTENT. */
		SubjectExpander.SubjectExpansionResult dates = expander.expand(List.of("dateIdentified", "eventDate"));
		SubjectExpander.SubjectExpansionResult marine = expander.expand(
				List.of("decimalLatitude", "decimalLongitude", "scientificName"));

		assertThat(dates.problems()).isEmpty();
		assertThat(dates.subjects()).extracting(subject -> subject.effectiveRecord().terms().get("dateIdentified")
				+ "/" + subject.effectiveRecord().terms().get("eventDate"))
				.containsExactly("2021-01-01/2020-06-01", "1999-01-01/2020-06-01");
		assertThat(dates.subjects()).allMatch(subject -> subject.subjectRef().relationName().equals("identification"));
		assertThat(marine.problems()).isEmpty();
		assertThat(marine.subjects()).extracting(subject -> subject.effectiveRecord().terms().get("scientificName")
				+ "@" + subject.effectiveRecord().terms().get("decimalLatitude"))
				.containsExactly("Aus bus@10.5", "Cus dus@10.5");
		assertThat(marine.subjects().get(0).effectiveRecord().provenanceByTerm().get("decimalLatitude"))
				.extracting(SourceCell::table)
				.containsExactly("event");
	}

	@Test
	void singleValuedSiblingsAreOverlaidAtCoreGrain() {
		CanonicalRecord core = core("occ-1", Map.of("occurrenceID", "occ-1"));
		SubjectExpander expander = new SubjectExpander(new RecordDataset(
				List.of(core),
				List.of(new RecordGraph(
						core,
						Map.of(
								"event", List.of(related("ev-1", "event", Map.of("eventDate", "2020-06-01"))),
								"identification", List.of(related("id-1", "identification",
										Map.of("dateIdentified", "2021-01-01"))))))));

		SubjectExpander.SubjectExpansionResult expansion = expander.expand(List.of("dateIdentified", "eventDate"));

		assertThat(expansion.problems()).isEmpty();
		assertThat(expansion.subjects()).singleElement().satisfies(subject -> {
			assertThat(subject.hasStructuredReference()).isFalse();
			assertThat(subject.effectiveRecord().terms())
					.containsEntry("dateIdentified", "2021-01-01")
					.containsEntry("eventDate", "2020-06-01");
		});
	}

	@Test
	void coreRecordWithoutGoverningRowsIsEvaluatedOnceWithBlankFields() {
		CanonicalRecord withRows = core("occ-1", Map.of("occurrenceID", "occ-1"));
		CanonicalRecord withoutRows = core("occ-2", Map.of("occurrenceID", "occ-2"));
		SubjectExpander expander = new SubjectExpander(new RecordDataset(
				List.of(withRows, withoutRows),
				List.of(
						new RecordGraph(withRows, Map.of("identification", List.of(
								related("id-1", "identification", Map.of("scientificName", "Aus bus")),
								related("id-2", "identification", Map.of("scientificName", "Cus dus"))))),
						new RecordGraph(withoutRows, Map.of("identification", List.of())))));

		SubjectExpander.SubjectExpansionResult expansion = expander.expand(List.of("scientificName"));

		assertThat(expansion.problems()).isEmpty();
		assertThat(expansion.subjects()).hasSize(3);
		assertThat(expansion.subjects().get(2)).satisfies(subject -> {
			assertThat(subject.coreRecordId()).isEqualTo("occ-2");
			assertThat(subject.hasStructuredReference()).isFalse();
			assertThat(subject.effectiveRecord().terms()).containsEntry("scientificName", "");
		});
	}

	@Test
	void joinKeyColumnsDoNotMakeARelationSupplyAField() {
		CanonicalRecord core = core("occ-1", Map.of("occurrenceID", "occ-1"));
		RecordDataset dataset = new RecordDataset(
				List.of(core),
				List.of(new RecordGraph(core, Map.of("identification", List.of(
						related("row-1", "identification", Map.of("occurrenceID", "occ-1", "scientificName", "Aus bus")),
						related("row-2", "identification", Map.of("occurrenceID", "occ-1", "scientificName", "Cus dus")))))),
				new org.filteredpush.bdq_workbench.model.DatasetInputDescription(
						org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewMode.STRUCTURED, "", "occurrence",
						1, List.of(),
						List.of(new org.filteredpush.bdq_workbench.model.RelationshipSchema(
								"identification", "occurrenceID", "occurrence", "occurrenceID", "identification")),
						List.of(), List.of()));

		SubjectExpander.SubjectExpansionResult expansion = new SubjectExpander(dataset).expand(List.of("occurrenceID"));

		assertThat(expansion.subjects()).singleElement()
				.satisfies(subject -> assertThat(subject.hasStructuredReference()).isFalse());
	}

	@Test
	void termOnBothGrainAndExpandedRowsEvaluatesTheGrainValueToo() {
		CanonicalRecord core = core("occ-1", Map.of("occurrenceID", "occ-1", "scientificName", "Aus bus"));
		SubjectExpander expander = new SubjectExpander(new RecordDataset(
				List.of(core),
				List.of(new RecordGraph(core, Map.of("identification", List.of(
						related("row-1", "identification", Map.of("scientificName", "Aus cus")),
						related("row-2", "identification", Map.of("scientificName", "Aus dus"))))))));

		SubjectExpander.SubjectExpansionResult expansion = expander.expand(List.of("scientificName"));

		assertThat(expansion.subjects()).extracting(subject -> subject.effectiveRecord().terms().get("scientificName"))
				.containsExactly("Aus bus", "Aus cus", "Aus dus");
		assertThat(expansion.subjects()).extracting(subject -> subject.subjectRef().sourceTable()
				+ ":" + subject.subjectRef().rowRef())
				.containsExactly("occurrence:occ-1", "identification:row-1", "identification:row-2");
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
