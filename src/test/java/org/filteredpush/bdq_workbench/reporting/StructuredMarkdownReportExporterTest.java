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

class StructuredMarkdownReportExporterTest {

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

	@Test
	void rendersQualityControlSectionsWithReadableNames() {
		String markdown = StructuredMarkdownReportExporter.renderMarkdown(ReportFixtures.qualityControlRun());

		assertThat(markdown).contains("> **External prerequisites not met:** 2 result(s), in Country code not empty (2)");
		assertThat(markdown).contains("- Records with quality for this use case: 1 of 3 record(s) meet all 2 "
				+ "multi-record QA measure(s) (post-amendment)\n");
		/* Run metadata reads in run order: selection (filters, then records selected), outcome last. */
		assertThat(markdown).containsSubsequence("- Use case: ", "- Input file: ", "- External prerequisites not met: ",
				"- Run finished: ", "- Record filters: ", "- Records selected for execution: ",
				"- Records with quality for this use case: ", "\n\n## ");
		assertThat(markdown).contains("| Geodetic datum standard | 2 → 0 of 3 | 0 / 0 |");
		assertThat(markdown).contains("| `locality` | column present, always empty | Locality and depth |");
		assertThat(markdown).contains("| 2 | `geodeticDatum`: WGS 84 → **EPSG:4326** | Geodetic datum standardized |");
		assertThat(markdown).contains("| MCZ:Herp:A-1 (occurrence.txt line 2) | — | geodeticDatum: WGS 84 → EPSG:4326 |\n"
				+ "| ↳ identification.txt line 3 (scientificName: Aus) | Scientific name found: NOT_COMPLIANT | — |\n");
		assertThat(markdown).contains("- Unresolved test — No implementation discovered\n");
		assertThat(markdown).contains("## High-impact action items\n\n- Review issue findings: 0 record(s) with "
				+ "confirmed issues, 0 with potential issues\n- Validation non-compliance after amendment: 1 finding(s) "
				+ "across 1 record(s)\n");
		assertThat(markdown).contains("  1. `geodeticDatum`: WGS 84 → **EPSG:4326** (Geodetic datum standardized) — "
				+ "2 record(s), 1 with fewer problems after amendment\n");
		assertThat(markdown).contains("## Measure differences between pre-amendment and post-amendment phases\n\nNo "
				+ "multi-record measures were produced");
		assertThat(markdown.indexOf("## High-impact action items")).isLessThan(markdown.indexOf("## Measure differences"));
		assertThat(markdown.indexOf("## Measure differences")).isLessThan(markdown.indexOf("## Records with quality"));
		assertThat(markdown).doesNotContain("urn:test:").doesNotContain("## Record `");
	}

	@Test
	void listsChangedMeasures() {
		ExecutionSummary summary = new ExecutionSummary(List.of(
				measureResponse("urn:test:count-coordinates", "Count compliant coordinates", Phase.PRE_AMENDMENT, "1", "4", "25.0"),
				measureResponse("urn:test:count-coordinates", "Count compliant coordinates", Phase.POST_AMENDMENT, "3", "4", "75.0")));

		String report = StructuredMarkdownReportExporter.renderMarkdown(summary);

		assertThat(report).contains("## Measure differences between pre-amendment and post-amendment phases\n\n"
				+ "### COUNT measures\n\n1 of 1 COUNT measure(s) changed after amendment.");
		assertThat(report).contains("#### Measures with pre/post differences");
		assertThat(report).contains("### QA measures\n\nNo multi-record QA measures were produced in this run.");
		assertThat(report).contains("- Count compliant coordinates: 1/4 (25.0%) -> 3/4 (75.0%) (+50 percentage point(s))");
	}

	@Test
	void listsQaMeasuresByResultWithPassRates() {
		ExecutionSummary summary = new ExecutionSummary(List.of(
				ReportFixtures.qaMeasureResponse("urn:test:qa-basis", "MULTIRECORD_MEASURE_QA_BASISOFRECORD_NOTEMPTY",
						Phase.PRE_AMENDMENT, "NOT_COMPLETE", "870", "1000", "87.0"),
				ReportFixtures.qaMeasureResponse("urn:test:qa-basis", "MULTIRECORD_MEASURE_QA_BASISOFRECORD_NOTEMPTY",
						Phase.POST_AMENDMENT, "NOT_COMPLETE", "900", "1000", "90.0"),
				ReportFixtures.qaMeasureResponse("urn:test:qa-date", "MULTIRECORD_MEASURE_QA_EVENTDATE_STANDARD",
						Phase.PRE_AMENDMENT, "NOT_COMPLETE", "999", "1000", "99.9"),
				ReportFixtures.qaMeasureResponse("urn:test:qa-date", "MULTIRECORD_MEASURE_QA_EVENTDATE_STANDARD",
						Phase.POST_AMENDMENT, "COMPLETE", "1000", "1000", "100.0")));

		String report = StructuredMarkdownReportExporter.renderMarkdown(summary);

		assertThat(report).contains("### COUNT measures\n\nNo multi-record COUNT measures were produced in this run.");
		assertThat(report).contains("### QA measures\n\n1 of 2 QA measure(s) changed result after amendment.");
		assertThat(report).contains("| MULTIRECORD_MEASURE_QA_EVENTDATE_STANDARD | **NOT_COMPLETE** — 99% of records were "
				+ "COMPLIANT (999 of 1000) | **COMPLETE** — 100% of records were COMPLIANT (1000 of 1000) | "
				+ "NOT_COMPLETE → COMPLETE |\n| MULTIRECORD_MEASURE_QA_BASISOFRECORD_NOTEMPTY | **NOT_COMPLETE** — 87% of "
				+ "records were COMPLIANT (870 of 1000) | **NOT_COMPLETE** — 90% of records were COMPLIANT (900 of "
				+ "1000) | no change |\n");
		assertThat(report).doesNotContain("percentage point");
	}

	@Test
	void rendersInputViewOverviewWithTableCountsAndIgnoredTables() {
		String markdown = StructuredMarkdownReportExporter.renderMarkdown(InputViewFixtures.structuredSummary());

		assertThat(markdown).contains("## Input data view\n\n");
		assertThat(markdown).contains("- View: **Structured view with multiplicity present** — Each row of event");
		assertThat(markdown).contains("- Grain table: `event` → 2 execution record(s), 2 selected after record filtering\n");
		assertThat(markdown).contains("- Input tables: 5 (2 used by the view, 2 ignored for lacking test bindings, "
				+ "1 not included)\n");
		assertThat(markdown).contains("  - occurrence: 4 related row(s) across 2 of 2 event record(s); 1 had more "
				+ "than one (max 3), each evaluated separately\n");
		assertThat(markdown).contains("| `occurrence` | OCCURRENCE | 4 | 3 | joined into view | child of event "
				+ "(eventID → eventID) | 1 | scientificName |\n");
		assertThat(markdown).contains("| `audit` | OTHER | 9 | 2 | ignored: no test bindings | not directly related "
				+ "to event | 0 | none |\n");
		assertThat(markdown).contains("Ignored in view construction (no test binding reads any of their terms): "
				+ "`measurement`, `audit`\n");
		assertThat(markdown.indexOf("## Input data view"))
				.isLessThan(markdown.indexOf("## High-impact action items"));
	}

	@Test
	void notesWhenInputViewWasNotRecorded() {
		String markdown = StructuredMarkdownReportExporter.renderMarkdown(new ExecutionSummary(List.of()));

		assertThat(markdown).contains("## Input data view\n\n- View: not recorded by ingest\n\n");
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
}
