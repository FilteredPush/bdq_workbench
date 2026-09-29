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
	void rendersQualityControlSectionsWithReadableNamesInOrder() {
		String html = StructuredHtmlReportExporter.renderHtml(ReportFixtures.qualityControlRun());

		assertThat(html).contains("<div class=\"data-notice\" role=\"note\"><strong>External prerequisites not met:"
				+ "</strong> 2 result(s), in Country code not empty (2)");
		assertThat(html).contains("<li><strong>Records with quality for this use case:</strong> 1 of 3 record(s) meet "
				+ "all 2 multi-record QA measure(s) (post-amendment)</li>");
		assertThat(html).contains("<p>Survey:77 (occurrence.txt line 3)</p>");
		assertThat(html).contains("<tr><td>Geodetic datum standard</td><td class=\"num\">2 → 0 of 3</td>");
		assertThat(html).contains("<tr><td><code>minimumDepthInMeters</code></td><td>no such column</td>"
				+ "<td>Locality and depth</td></tr>");
		assertThat(html).contains("<tr><td class=\"num\">2</td><td><code>geodeticDatum</code>: WGS 84 → "
				+ "<strong>EPSG:4326</strong></td><td>Geodetic datum standardized</td>");
		assertThat(html).contains("<tr><td>MCZ:Herp:A-1 (occurrence.txt line 2)</td><td>Scientific name found (1 of 2 "
				+ "rows)</td><td>geodeticDatum: WGS 84 → EPSG:4326</td></tr>");
		assertThat(html).contains("<li>Unresolved test <span class=\"muted\">— No implementation discovered</span></li>");
		assertThat(html).doesNotContain("urn:test:").doesNotContain("<h2>Record <code>");
		List<String> order = List.of("<h2>Run metadata</h2>", "<h2>Input data view</h2>",
				"<h2>Records with quality for this use case</h2>", "<h2>Quality control by test</h2>",
				"<h2>Information elements empty in every record</h2>", "<h2>Proposed amendments</h2>",
				"<h2>Records needing attention</h2>", "<h2>Tests that could not run</h2>");
		for (int index = 1; index < order.size(); index++) {
			assertThat(html.indexOf(order.get(index - 1))).isLessThan(html.indexOf(order.get(index)));
		}
	}

	@Test
	void rendersMeasureDifferencesAsADumbbellChart() {
		ExecutionSummary summary = new ExecutionSummary(List.of(
				measureResponse("urn:test:count-coordinates", "Count compliant coordinates", Phase.PRE_AMENDMENT, "1", "4", "25.0"),
				measureResponse("urn:test:count-coordinates", "Count compliant coordinates", Phase.POST_AMENDMENT, "3", "4", "75.0"),
				measureResponse("urn:test:count-dates", "Count complete dates", Phase.PRE_AMENDMENT, "2", "4", "50.0"),
				measureResponse("urn:test:count-dates", "Count complete dates", Phase.POST_AMENDMENT, "2", "4", "50.0")));

		String report = StructuredHtmlReportExporter.renderHtml(summary);

		assertThat(report).contains("<h2>Measure differences between pre-amendment and post-amendment phases</h2>");
		assertThat(report).contains("1 of 2 measure(s) changed after amendment: 1 improved, 0 declined.");
		assertThat(report).contains("<line x1=\"25%\" x2=\"75%\" y1=\"13\" y2=\"13\" stroke=\"var(--mc-link)\"");
		assertThat(report).contains("<circle cx=\"25%\" cy=\"13\" r=\"6\" fill=\"#fcfcfb\" stroke=\"var(--mc-pre)\"");
		assertThat(report).contains("<circle cx=\"75%\" cy=\"13\" r=\"6\" fill=\"var(--mc-post)\"");
		assertThat(report).contains("<span class=\"mc-delta up\">▲ +50 pts</span>");
		assertThat(report).contains("<summary>Table view</summary>").contains("<h3>Measures with no differences</h3>");
		assertThat(report.indexOf("Changed after amendment (1)")).isLessThan(report.indexOf("Unchanged (1)"));
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
				.isLessThan(html.indexOf("<h2>Records with quality for this use case</h2>"));
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
