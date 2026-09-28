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

class StructuredHtmlReportExporterTest {

	@Test
	void rendersStructuredRecordSectionsWithNestedDetailSelectors() throws Exception {
		StructuredHtmlReportExporter exporter = new StructuredHtmlReportExporter();
		Response detailOne = structuredDetail("identification", "identification.txt", "row-2", "COMPLIANT", "first detail");
		Response detailTwo = structuredDetail("measurement", "flattened-view.csv", "row-7", "NOT_COMPLIANT", "second detail");
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
				Instant.now(),
				Instant.now(),
				null,
				true,
				List.of(detailOne.subjectRef(), detailTwo.subjectRef()));

		ExecutionSummary summary = new ExecutionSummary(
				List.of(detailOne, detailTwo, rollup),
				new ExecutionSummaryMetadata("urn:usecase:1", "Use Case One", "/tmp/input.csv", 3, 1, Map.of(), Map.of()),
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
				Instant.now(),
				Instant.now());

		ExecutionSummary summary = new ExecutionSummary(
				List.of(flatResponse),
				new ExecutionSummaryMetadata("urn:usecase:1", "Use Case One", "/tmp/input.csv", 3, 1, Map.of(), Map.of()),
				new RecordDataset(List.of(new CanonicalRecord("record-1", Map.of("dwc:occurrenceID", "record-1")))));

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		exporter.export(summary, output);
		String report = output.toString(StandardCharsets.UTF_8);

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
}
