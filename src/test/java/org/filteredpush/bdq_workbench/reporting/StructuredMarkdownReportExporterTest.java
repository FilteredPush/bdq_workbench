package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

	@Test
	void rendersStructuredRecordSectionsWithNestedDetailSelectors() throws Exception {
		StructuredMarkdownReportExporter exporter = new StructuredMarkdownReportExporter();
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

		ExecutionSummary summary = new ExecutionSummary(
				List.of(detailOne, detailTwo, rollup, issue, amendment),
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

		assertThat(exporter.format()).isEqualTo("structured");
		assertThat(exporter.fileExtension()).isEqualTo("md");
		assertThat(report).contains("# BDQ Workbench Structured Report");
		assertThat(report).contains("## Run metadata");
		assertThat(report).contains("- Input file: /tmp/input.csv");
		assertThat(report).contains("- Run started: 2026-09-29T01:00:00Z");
		assertThat(report).contains("## High-impact action items");
		assertThat(report).contains("- Review issue findings: 1 confirmed issue response(s), 0 potential issue response(s)");
		assertThat(report).contains("Coordinates valid — Missing coordinates — 1 response(s) across 1 record(s)");
		assertThat(report).contains("Fill missing coordinates: dwc:decimalLatitude → 42.0 — 1 response(s) across 1 record(s)");
		assertThat(report).contains("## Record `record-1`");
		assertThat(report).contains("### POST_AMENDMENT · VALIDATION · `urn:test:structured`");
		assertThat(report).contains("- Summary: RUN_HAS_RESULT / NOT_COMPLIANT — rollup");
		assertThat(report).contains("Contributing subjects: identification.txt#row=2, flattened-view.csv#row=7");
		assertThat(report).contains("<details>");
		assertThat(report).contains("Subject `identification` at `identification.txt#row=2`");
		assertThat(report).contains("Subject `measurement` at `flattened-view.csv#row=7`");
	}

	@Test
	void flatRunDegradesToSingleLevelPerRecordSummary() throws Exception {
		StructuredMarkdownReportExporter exporter = new StructuredMarkdownReportExporter();
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

		assertThat(report).contains("## Run metadata");
		assertThat(report).contains("- Record filters:");
		assertThat(report).contains("  - dwc:country = Canada");
		assertThat(report).contains("## High-impact action items");
		assertThat(report).contains("- Review issue findings: 0 confirmed issue response(s), 0 potential issue response(s)");
		assertThat(report).contains("## Record `record-1`");
		assertThat(report).contains("### PRE_AMENDMENT · VALIDATION · `urn:test:flat`");
		assertThat(report).contains("- Summary: RUN_HAS_RESULT / COMPLIANT — flat ok");
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
}
