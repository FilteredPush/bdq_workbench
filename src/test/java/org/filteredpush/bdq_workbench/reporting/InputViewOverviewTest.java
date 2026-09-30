package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.reporting.InputViewOverview.TableOverview;
import org.filteredpush.bdq_workbench.reporting.InputViewOverview.TableRole;
import org.junit.jupiter.api.Test;

/**
 * Tests how {@link InputViewOverview} classifies input tables against a run's bindings.
 */
class InputViewOverviewTest {

	@Test
	void structuredViewClassifiesTablesByBoundTermsAndReach() {
		InputViewOverview overview = InputViewOverview.from(InputViewFixtures.structuredSummary());

		assertThat(overview.isKnown()).isTrue();
		assertThat(overview.modeLabel()).isEqualTo("Structured view with multiplicity present");
		assertThat(overview.tables())
				.extracting(TableOverview::name, TableOverview::role)
				.containsExactly(
						tuple("event", TableRole.GRAIN),
						tuple("occurrence", TableRole.CONTRIBUTING),
						tuple("measurement", TableRole.IGNORED_NO_BINDINGS),
						tuple("media", TableRole.NOT_INCLUDED),
						tuple("audit", TableRole.IGNORED_NO_BINDINGS));
		assertThat(overview.ignoredTables()).extracting(TableOverview::name).containsExactly("measurement", "audit");
		TableOverview occurrence = overview.tables().get(1);
		assertThat(occurrence.suppliedTerms()).containsExactly("scientificName");
		assertThat(occurrence.testCount()).isEqualTo(1);
		assertThat(occurrence.relationToGrain()).isEqualTo("child of event (eventID → eventID)");
		assertThat(overview.tables().get(3).relationToGrain()).isEqualTo("not directly related to event");
		assertThat(overview.multiplicityNotes()).containsExactly(
				"occurrence: 4 related row(s) across 2 of 2 event record(s); 1 had more than one (max 3), "
						+ "each evaluated separately",
				"measurement: 1 related row(s) across 1 of 2 event record(s); at most one per record");
	}

	@Test
	void flattenedViewCountsOnlyMappedTermsAndReportsCollapsePolicy() {
		InputViewOverview overview = InputViewOverview.from(InputViewFixtures.flattenedSummary());

		assertThat(overview.modeLabel()).isEqualTo("Flattened view");
		assertThat(overview.tables())
				.extracting(TableOverview::name, TableOverview::role)
				.containsExactly(
						tuple("event", TableRole.GRAIN),
						tuple("occurrence", TableRole.CONTRIBUTING),
						tuple("measurement", TableRole.IGNORED_NO_BINDINGS),
						tuple("media", TableRole.NOT_INCLUDED),
						tuple("audit", TableRole.IGNORED_NO_BINDINGS));
		assertThat(overview.tables().get(0).suppliedTerms()).containsExactly("eventDate");
		assertThat(overview.multiplicityNotes()).singleElement()
				.asString()
				.contains("collapsed by FIRST_ROW");
	}

	@Test
	void expandedViewUsesMappedTermsAndReportsPerRowEvaluation() {
		InputViewOverview overview = InputViewOverview.from(InputViewFixtures.expandedViewSummary());

		assertThat(overview.modeLabel()).isEqualTo("Structured view with multiplicity present");
		assertThat(overview.modeExplanation()).contains("joined with EXPAND were retained");
		assertThat(overview.tables().get(0).suppliedTerms()).containsExactly("eventDate");
		assertThat(overview.tables().get(1).role()).isEqualTo(TableRole.CONTRIBUTING);
		assertThat(overview.multiplicityNotes()).singleElement().asString()
				.endsWith("1 had more than one (max 3), each evaluated separately");
		assertThat(StructuredHtmlReportExporter.renderHtml(InputViewFixtures.expandedViewSummary()))
				.contains(">expand: per row</text>");
	}

	@Test
	void filteredRunMeasuresMultiplicityOverTheSelectedRecordsOnly() {
		org.filteredpush.bdq_workbench.model.DatasetInputDescription description =
				new org.filteredpush.bdq_workbench.model.DatasetInputDescription(
						org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewMode.STRUCTURED, "view.json",
						"occurrence", 3,
						List.of(new org.filteredpush.bdq_workbench.model.DatasetInputDescription.InputTable(
										"occurrence", "OCCURRENCE", 3, List.of("occurrenceID")),
								new org.filteredpush.bdq_workbench.model.DatasetInputDescription.InputTable(
										"identification", "TAXON", 6, List.of("occurrenceID", "scientificName"))),
						List.of(new org.filteredpush.bdq_workbench.model.RelationshipSchema("identification",
								"occurrenceID", "occurrence", "occurrenceID", "identification")),
						List.of(new org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation(
								"identification", "identification",
								org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy.EXPAND, 3, 2, 3, 6,
								List.of("scientificName"), java.util.Map.of("occ-1", 3, "occ-2", 2, "occ-3", 1))),
						List.of("occurrenceID"));
		ExecutionSummary summary = new ExecutionSummary(List.of(), null,
				new org.filteredpush.bdq_workbench.model.RecordDataset(List.of(
						new org.filteredpush.bdq_workbench.model.CanonicalRecord("occ-2", java.util.Map.of()),
						new org.filteredpush.bdq_workbench.model.CanonicalRecord("occ-3", java.util.Map.of())),
						List.of(), description));

		InputViewOverview overview = InputViewOverview.from(summary);

		assertThat(overview.filtered()).isTrue();
		assertThat(overview.inputRecordCount()).isEqualTo(3);
		assertThat(overview.multiplicityNotes()).containsExactly("identification: 3 related row(s) across 2 of 2 "
				+ "occurrence record(s); 1 had more than one (max 2), each evaluated separately");
		assertThat(overview.tables()).extracting(TableOverview::recordCountLabel).containsExactly("2 of 3", "3 of 6");
		String html = StructuredHtmlReportExporter.renderHtml(summary);
		assertThat(html).contains("<strong>Related-row multiplicity in the 2 selected record(s):</strong>");
		assertThat(html).contains(">Flat occurrence record</text>").contains(">Expanded identification rows</text>")
				.contains(">3 row(s) for 2 record(s), up to 2 each</text>").contains(">1 : n</text>");
	}

	@Test
	void unrecordedDescriptionIsReportedAsUnknown() {
		InputViewOverview overview = InputViewOverview.from(new ExecutionSummary(List.of()));

		assertThat(overview.isKnown()).isFalse();
		assertThat(overview.tables()).isEmpty();
		assertThat(overview.modeLabel()).isEqualTo("Not recorded");
	}

	@Test
	void suppliedTermsSummaryListsAtMostTheRequestedNumber() {
		TableOverview table = new TableOverview("t", "", 1, 4, TableRole.GRAIN, "grain table",
				List.of("a", "b", "c", "d"), 1, null);

		assertThat(table.suppliedTermsSummary(2)).isEqualTo("a, b (+2 more)");
		assertThat(table.suppliedTermsSummary(4)).isEqualTo("a, b, c, d");
	}
}
