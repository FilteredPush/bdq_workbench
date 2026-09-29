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
				.contains(">expand: 1 subject per row</text>");
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
