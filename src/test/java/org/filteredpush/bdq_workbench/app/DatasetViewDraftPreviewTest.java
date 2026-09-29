package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.ingest.RelationalIngestResult;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.junit.jupiter.api.Test;

/**
 * Tests the dataset-view builder's preview rows, observed multiplicity, and policy warnings.
 */
class DatasetViewDraftPreviewTest {

	@Test
	void expandedJoinShowsRelatedRowsBeneathEachGrainRecord() {
		DatasetViewDraftPreview preview = DatasetViewDraftPreview.build(relational(), view(
				DatasetViewCardinalityPolicy.EXPAND));

		assertThat(preview.columns()).containsExactly("recordId", "View row", "occurrenceID", "scientificName");
		assertThat(preview.rows()).containsExactly(
				List.of("occ-1", "occurrence", "occ-1", ""),
				List.of("occ-1", "  ↳ identification row 1 of 2", "", "Abies"),
				List.of("occ-1", "  ↳ identification row 2 of 2", "", "Picea"),
				List.of("occ-2", "occurrence", "occ-2", ""),
				List.of("occ-2", "  ↳ no identification rows (tested once, blank)", "", ""));
		assertThat(preview.summary()).anySatisfy(line -> assertThat(line)
				.startsWith("identification [EXPAND]: 2 related row(s), up to 2 per grain record")
				.contains("run once per related row (about 3 evaluation(s) per such test)"));
		assertThat(preview.warnings()).isEmpty();
	}

	@Test
	void firstRowWithMultiplicityWarnsThatRowsAreDropped() {
		DatasetViewDraftPreview preview = DatasetViewDraftPreview.build(relational(), view(
				DatasetViewCardinalityPolicy.FIRST_ROW));

		assertThat(preview.rows()).hasSize(2);
		assertThat(preview.warnings()).singleElement().asString()
				.isEqualTo("identification [FIRST_ROW]: 1 grain record(s) have more than one row (up to 2); only "
						+ "the first row is used. Choose EXPAND to test every row.");
		assertThat(preview.renderDiagnostics()).contains("\nWarnings:\n - identification [FIRST_ROW]");
	}

	@Test
	void aggregateAndRejectExplainTheirEffectOnMappedTerms() {
		assertThat(DatasetViewDraftPreview.build(relational(), view(DatasetViewCardinalityPolicy.AGGREGATE)).warnings())
				.anySatisfy(line -> assertThat(line).contains("joined with \" | \", which will usually fail tests on "
						+ "scientificName"));
		assertThat(DatasetViewDraftPreview.build(relational(), view(DatasetViewCardinalityPolicy.REJECT)).warnings())
				.anySatisfy(line -> assertThat(line).contains("scientificName will be empty for those records"));
	}

	@Test
	void warnsWhenMoreThanOneJoinExpands() {
		DatasetView view = new DatasetView("occurrence", "fp",
				List.of(
						new DatasetViewJoin("identification", "identification", DatasetViewCardinalityPolicy.EXPAND),
						new DatasetViewJoin("measurement", "measurement", DatasetViewCardinalityPolicy.EXPAND)),
				List.of(
						new DatasetViewMapping("scientificName", "identification", "scientificName"),
						new DatasetViewMapping("measurementValue", "measurement", "measurementValue")));

		assertThat(DatasetViewDraftPreview.build(relational(), view).warnings())
				.anySatisfy(line -> assertThat(line).startsWith("More than one table is expanded (identification, "
						+ "measurement)"));
	}

	@Test
	void observedMultiplicityDescribesRowsPerGrainRecord() {
		assertThat(DatasetViewDraftPreview.observedMultiplicity(relational(), "identification"))
				.isEqualTo("up to 2 per record (1 with >1)");
		assertThat(DatasetViewDraftPreview.observedMultiplicity(relational(), "event")).isEqualTo("0..1 per record");
		assertThat(DatasetViewDraftPreview.observedMultiplicity(relational(), "measurement"))
				.isEqualTo("no related rows");
	}

	private static DatasetView view(DatasetViewCardinalityPolicy policy) {
		return new DatasetView("occurrence", "fp",
				List.of(new DatasetViewJoin("identification", "identification", policy)),
				List.of(
						new DatasetViewMapping("occurrenceID", "occurrence", "occurrenceID"),
						new DatasetViewMapping("scientificName", "identification", "scientificName")));
	}

	private static RelationalIngestResult relational() {
		CanonicalRecord first = new CanonicalRecord("occ-1", Map.of("occurrenceID", "occ-1"));
		CanonicalRecord second = new CanonicalRecord("occ-2", Map.of("occurrenceID", "occ-2"));
		return new RelationalIngestResult(
				List.of(
						new RecordGraph(first, Map.of(
								"identification", List.of(
										new CanonicalRecord("row-1", Map.of("scientificName", "Abies")),
										new CanonicalRecord("row-2", Map.of("scientificName", "Picea"))),
								"event", List.of(new CanonicalRecord("ev-1", Map.of("eventDate", "2020"))))),
						new RecordGraph(second, Map.of("identification", List.of(), "event", List.of()))),
				new DatasetSchema(List.of(), List.of(), "fp"),
				List.of());
	}
}
