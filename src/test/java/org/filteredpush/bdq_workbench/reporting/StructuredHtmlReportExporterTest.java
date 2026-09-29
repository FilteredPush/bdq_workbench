package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SubjectRef;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class StructuredHtmlReportExporterTest {

	@Test
	void rendersStructuredRecordSectionsWithNestedDetailSelectors() throws Exception {
		StructuredHtmlReportExporter exporter = new StructuredHtmlReportExporter();
		Instant startedAt = Instant.parse("2026-09-29T01:00:00Z");
		Instant finishedAt = Instant.parse("2026-09-29T01:05:00Z");
		Response detailOne = structuredDetail("identification", "identification.txt", "row-2", "COMPLIANT", "first detail");
		Response detailTwo = structuredDetail("measurement", "flattened-view.csv", "row-7", "NOT_COMPLIANT", "Missing coordinates");
		Response rollup = new Response(
				"record-1",
				"urn:test:structured",
				TestType.VALIDATION,
				"org.example.StructuredValidator",
				"validate",
				Phase.POST_AMENDMENT,
				Map.of(),
				OutcomeStatus.FAILED,
				"RUN_HAS_RESULT",
				"NOT_COMPLIANT",
				"rollup",
				"rollup",
				Map.of(),
				startedAt,
				finishedAt,
				null,
				true,
				List.of(detailOne.subjectRef(), detailTwo.subjectRef()));
		Response issue = new Response(
				"record-1",
				"urn:test:issue",
				TestType.ISSUE,
				"org.example.IssueDetector",
				"detect",
				Phase.POST_AMENDMENT,
				Map.of(),
				OutcomeStatus.FAILED,
				"RUN_HAS_RESULT",
				"IS_ISSUE",
				"Coordinate issue",
				"Coordinate issue",
				Map.of(),
				startedAt,
				finishedAt);
		Response amendment = new Response(
				"record-1",
				"urn:test:amendment",
				TestType.AMENDMENT,
				"org.example.Amender",
				"amend",
				Phase.AMENDMENT,
				Map.of(),
				OutcomeStatus.AMENDED,
				"AMENDED",
				"",
				"Filled in coordinates",
				"Filled in coordinates",
				Map.of("dwc:decimalLatitude", "42.0"),
				startedAt,
				finishedAt);
		Response preMeasure = measureResponse(
				"urn:test:count",
				"Count compliant coordinates",
				Phase.PRE_AMENDMENT,
				"1",
				"4",
				"25.0");
		Response postMeasure = measureResponse(
				"urn:test:count",
				"Count compliant coordinates",
				Phase.POST_AMENDMENT,
				"3",
				"4",
				"75.0");

		ExecutionSummary summary = new ExecutionSummary(
				List.of(detailOne, detailTwo, rollup, issue, amendment, preMeasure, postMeasure),
				new ExecutionSummaryMetadata(
						"urn:usecase:1",
						"Use Case One",
						"/tmp/input.csv",
						3,
						1,
						3,
						1,
						Map.of(),
						Map.of(
								"urn:test:structured", "Coordinates valid",
								"urn:test:issue", "Coordinate issue",
								"urn:test:amendment", "Fill missing coordinates"),
						Map.of(),
						Map.of()),
				new RecordDataset(List.of(new CanonicalRecord(
						"record-1",
						Map.of("dwc:occurrenceID", "record-1", "dwc:countryCode", "GL")))));

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		exporter.export(summary, output);
		String report = output.toString(StandardCharsets.UTF_8);

		assertThat(exporter.format()).isEqualTo("structured-html");
		assertThat(exporter.fileExtension()).isEqualTo("html");
		assertThat(report).contains("<!DOCTYPE html>");
		assertThat(report).contains("<h1>BDQ Workbench Structured Report</h1>");
		assertThat(report).contains("<h2>Run metadata</h2>");
		assertThat(report).contains("<strong>Input file:</strong> /tmp/input.csv");
		assertThat(report).contains("<strong>Run started:</strong> 2026-09-29T01:00:00Z");
		assertThat(report).contains("<h2>High-impact action items</h2>");
		assertThat(report).contains("<strong>Review issue findings:</strong> 1 confirmed issue response(s), 0 potential issue response(s)");
		assertThat(report).contains("Coordinates valid — Missing coordinates — 1 response(s) across 1 record(s)");
		assertThat(report).contains("Fill missing coordinates: dwc:decimalLatitude → 42.0 — 1 response(s) across 1 record(s)");
		assertThat(report).contains("<h2>Measure differences between pre-amendment and post-amendment phases</h2>");
		assertThat(report).contains("Count compliant coordinates");
		assertThat(report).contains("1/4 (25.0%)");
		assertThat(report).contains("3/4 (75.0%)");
		assertThat(report).contains("+50 percentage point(s)");
		assertThat(report).contains("class=\"measure-fill pre\" style=\"width: 25%;\"");
		assertThat(report).contains("class=\"measure-fill post\" style=\"width: 75%;\"");
		assertThat(report).contains("<h2>Record <code>record-1</code></h2>");
		assertThat(report).contains("<h3>POST_AMENDMENT · VALIDATION · <code>urn:test:structured</code></h3>");
		assertThat(report).contains("<strong>Summary:</strong> RUN_HAS_RESULT / NOT_COMPLIANT — rollup");
		assertThat(report).contains("identification.txt#row=2, flattened-view.csv#row=7");
		assertThat(report).contains("<details>");
		assertThat(report).contains("Subject <code>identification</code> at <code>identification.txt#row=2</code>");
		assertThat(report).contains("Subject <code>measurement</code> at <code>flattened-view.csv#row=7</code>");
	}

	@Test
	void flatRunRendersSimplifiedSingleLevelHtmlSummary() throws Exception {
		StructuredHtmlReportExporter exporter = new StructuredHtmlReportExporter();
		Instant startedAt = Instant.parse("2026-09-29T02:00:00Z");
		Response flatResponse = new Response(
				"record-1",
				"urn:test:flat",
				TestType.VALIDATION,
				"org.example.FlatValidator",
				"validate",
				Phase.PRE_AMENDMENT,
				Map.of(),
				OutcomeStatus.PASSED,
				"RUN_HAS_RESULT",
				"COMPLIANT",
				"flat ok",
				"flat ok",
				Map.of(),
				startedAt,
				startedAt);

		ExecutionSummary summary = new ExecutionSummary(
				List.of(flatResponse),
				new ExecutionSummaryMetadata(
						"urn:usecase:1",
						"Use Case One",
						"/tmp/input.csv",
						3,
						1,
						3,
						1,
						Map.of("dwc:country", List.of("Canada")),
						Map.of("urn:test:flat", "Flat validation"),
						Map.of(),
						Map.of()),
				new RecordDataset(List.of(new CanonicalRecord("record-1", Map.of("dwc:occurrenceID", "record-1")))));

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		exporter.export(summary, output);
		String report = output.toString(StandardCharsets.UTF_8);

		assertThat(report).contains("<h2>Run metadata</h2>");
		assertThat(report).contains("<strong>Record filters:</strong>");
		assertThat(report).contains("dwc:country = Canada");
		assertThat(report).contains("<h2>High-impact action items</h2>");
		assertThat(report).contains("<strong>Review issue findings:</strong> 0 confirmed issue response(s), 0 potential issue response(s)");
		assertThat(report).contains("<h2>Record <code>record-1</code></h2>");
		assertThat(report).contains("<h3>PRE_AMENDMENT · VALIDATION · <code>urn:test:flat</code></h3>");
		assertThat(report).contains("<strong>Summary:</strong> RUN_HAS_RESULT / COMPLIANT — flat ok");
		assertThat(report).contains("<li>RUN_HAS_RESULT / COMPLIANT — flat ok</li>");
		assertThat(report).doesNotContain("<details>");
	}

	private static Response structuredDetail(
			String relationName,
			String sourceLocation,
			String rowRef,
			String result,
			String comment) {
		return new Response(
				"record-1",
				"urn:test:structured",
				TestType.VALIDATION,
				"org.example.StructuredValidator",
				"validate",
				Phase.POST_AMENDMENT,
				Map.of(),
				"COMPLIANT".equals(result) ? OutcomeStatus.PASSED : OutcomeStatus.FAILED,
				"RUN_HAS_RESULT",
				result,
				comment,
				comment,
				Map.of(),
				Instant.now(),
				Instant.now(),
				new SubjectRef("record-1", relationName, relationName, sourceLocation, rowRef),
				false,
				List.of());
	}

	private static Response measureResponse(
			String testId,
			String label,
			Phase phase,
			String matchingCount,
			String totalRecords,
			String percentage) {
		return new Response(
				"MULTIRECORD",
				testId,
				TestType.MEASURE,
				BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
				BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
				phase,
				Map.of(
						BuiltInMeasureSpec.KIND_KEY, BuiltInMeasureSpec.MeasureKind.COUNT.name(),
						BuiltInMeasureSpec.MEASURE_LABEL_KEY, label,
						BuiltInMeasureSpec.MATCHING_COUNT_KEY, matchingCount,
						BuiltInMeasureSpec.TOTAL_RECORDS_KEY, totalRecords,
						BuiltInMeasureSpec.PERCENTAGE_KEY, percentage),
				OutcomeStatus.PASSED,
				"RUN_HAS_RESULT",
				matchingCount,
				matchingCount,
				matchingCount,
				Map.of(),
				Instant.now(),
				Instant.now());
	}

	@Test
	void rendersInputViewOverviewWithDiagramAndIgnoredTables() {
		String html = StructuredHtmlReportExporter.renderHtml(InputViewFixtures.structuredSummary());

		assertThat(html).contains("<h2>Input data view</h2>");
		assertThat(html).contains("<strong>Structured view with multiplicity present.</strong>");
		assertThat(html).contains("<li><strong>View source:</strong> automatic relational ingest</li>");
		assertThat(html).contains("<code>event</code> → 2 execution record(s), 2 selected after record filtering");
		assertThat(html).contains("5 (2 used by the view, 2 ignored for lacking test bindings, 1 not included)");
		assertThat(html).contains("<svg viewBox=\"0 0 880 ");
		assertThat(html).contains("<g class=\"vw-box vw-grain\">");
		assertThat(html).contains("<g class=\"vw-box vw-contrib\">");
		assertThat(html).contains("<g class=\"vw-box vw-ignored\">");
		assertThat(html).contains("<g class=\"vw-box vw-notincl\">");
		assertThat(html).contains("1 : n (max 3) · 1 bound term(s)");
		assertThat(html).contains("<path class=\"vw-rel\" ");
		assertThat(html).contains("<path class=\"vw-rel inactive\" ");
		assertThat(html).contains("rows retained");
		assertThat(html).contains("<td><code>occurrence</code></td><td>OCCURRENCE</td><td>4</td><td>3</td>"
				+ "<td>joined into view</td><td>child of event (eventID → eventID)</td><td>1</td>"
				+ "<td>scientificName</td></tr>");
		assertThat(html).contains("<strong>Ignored in view construction</strong> (no test binding reads any of their "
				+ "terms): <code>measurement</code>, <code>audit</code>");
		assertThat(html.indexOf("<h2>Input data view</h2>"))
				.isLessThan(html.indexOf("<h2>High-impact action items</h2>"));
	}

	@Test
	void rendersFlattenedViewCollapsePolicyOnDiagramArrows() {
		String html = StructuredHtmlReportExporter.renderHtml(InputViewFixtures.flattenedSummary());

		assertThat(html).contains("<strong>Flattened view.</strong>");
		assertThat(html).contains(">first row</text>");
		assertThat(html).contains("related rows collapsed per record");
		assertThat(html).contains("collapsed by FIRST_ROW");
	}

	@Test
	void notesWhenInputViewWasNotRecorded() {
		String html = StructuredHtmlReportExporter.renderHtml(new ExecutionSummary(List.of()));

		assertThat(html).contains("<p><em>The view used to run the tests was not recorded by ingest.</em></p>");
		assertThat(html).doesNotContain("<svg");
	}
}
