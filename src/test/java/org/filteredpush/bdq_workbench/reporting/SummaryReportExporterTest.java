package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class SummaryReportExporterTest {

    @Test
    void exportsPhaseSpecificCountAndQaMeasureSections() throws Exception {
        SummaryReportExporter exporter = new SummaryReportExporter();
        ExecutionSummary summary = new ExecutionSummary(
                List.of(
                        new Response(
                                "r1",
                                "urn:test:validation",
                                TestType.VALIDATION,
                                "example.Impl",
                                "validate",
                                Phase.PRE_AMENDMENT,
                                Map.of(),
                                OutcomeStatus.PASSED,
                                "RUN_HAS_RESULT",
                                "COMPLIANT",
                                "ok",
                                "ok",
                                Map.of(),
                                Instant.now(),
                                Instant.now()),
                        measureResponse(
                                "urn:test:count",
                                "MULTIRECORD_MEASURE_COUNT_COMPLIANT_BASISOFRECORD_NOTEMPTY",
                                Phase.PRE_AMENDMENT,
                                BuiltInMeasureSpec.MeasureKind.COUNT,
                                "1",
                                Map.of(
                                        BuiltInMeasureSpec.MATCHING_COUNT_KEY, "1",
                                        BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "2",
                                        BuiltInMeasureSpec.PERCENTAGE_KEY, "50.0")),
                        measureResponse(
                                "urn:test:count",
                                "MULTIRECORD_MEASURE_COUNT_COMPLIANT_BASISOFRECORD_NOTEMPTY",
                                Phase.POST_AMENDMENT,
                                BuiltInMeasureSpec.MeasureKind.COUNT,
                                "2",
                                Map.of(
                                        BuiltInMeasureSpec.MATCHING_COUNT_KEY, "2",
                                        BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "2",
                                        BuiltInMeasureSpec.PERCENTAGE_KEY, "100.0")),
                        measureResponse(
                                "urn:test:qa",
                                "MULTIRECORD_MEASURE_QA_MINDEPTH_LESSTHAN_MAXDEPTH",
                                Phase.PRE_AMENDMENT,
                                BuiltInMeasureSpec.MeasureKind.QA,
                                "NOT_COMPLETE",
                                Map.of()),
                        measureResponse(
                                "urn:test:qa",
                                "MULTIRECORD_MEASURE_QA_MINDEPTH_LESSTHAN_MAXDEPTH",
                                Phase.POST_AMENDMENT,
                                BuiltInMeasureSpec.MeasureKind.QA,
                                "COMPLETE",
                                Map.of())),
                new ExecutionSummaryMetadata(
                        "urn:usecase:1",
                        "Use Case One",
                        "/tmp/input.csv",
                        7,
                        2,
                        Map.of(
                                "dwc:countryCode=RU", 3L,
                                "dwc:occurrenceStatus=present", 2L),
                        Map.of(
                                "dwc:countryCode: SU -> RU", 4L,
                                "dwc:eventDate: 2024 -> 2024-01-01", 1L)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.export(summary, output);
        String report = output.toString(StandardCharsets.UTF_8);

        assertThat(report).contains("Use case: urn:usecase:1 (Use Case One)\n");
        assertThat(report).contains("Input file: /tmp/input.csv\n");
        assertThat(report).contains("Darwin Core terms present in input file: 7\n");
        assertThat(report).contains("SingleRecords in input file: 2\n");
        assertThat(report).contains("Counts represent normalized BDQ response rows emitted during execution:\n");
        assertThat(report).contains("By phase:\n - PRE_AMENDMENT: 3\n - POST_AMENDMENT: 2\n");
        assertThat(report).contains("By response status:\n - RUN_HAS_RESULT: 5\n");
        assertThat(report).contains("By response result:");
        assertThat(report).contains(" - COMPLETE: 1");
        assertThat(report).contains(" - COMPLIANT: 1");
        assertThat(report).contains(" - NOT_COMPLETE: 1");
        assertThat(report).doesNotContain(" - 1: 1");
        assertThat(report).doesNotContain(" - 2: 1");
        assertThat(report).contains("Multi-record COUNT measures:");
        assertThat(report).contains("Pre-amendment: 1/2 (50.0%)");
        assertThat(report).contains("Post-amendment: 2/2 (100.0%)");
        assertThat(report).contains("Multi-record QA measures:");
        assertThat(report).contains("Pre-amendment: NOT_COMPLETE");
        assertThat(report).contains("Post-amendment: COMPLETE");
        assertThat(report).contains("Top filled-in amendment values:");
        assertThat(report).contains(" - dwc:countryCode=RU: 3");
        assertThat(report).contains("Top amended original -> proposed values:");
        assertThat(report).contains(" - dwc:countryCode: SU -> RU: 4");
    }

    private static Response measureResponse(
            String testId,
            String label,
            Phase phase,
            BuiltInMeasureSpec.MeasureKind kind,
            String responseResult,
            Map<String, String> extras) {
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        parameters.put(BuiltInMeasureSpec.KIND_KEY, kind.name());
        parameters.put(BuiltInMeasureSpec.MEASURE_LABEL_KEY, label);
        parameters.putAll(extras);
        return new Response(
                "MULTIRECORD",
                testId,
                TestType.MEASURE,
                BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
                BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
                phase,
                Map.copyOf(parameters),
                OutcomeStatus.PASSED,
                "RUN_HAS_RESULT",
                responseResult,
                responseResult,
                responseResult,
                Map.of(),
                Instant.now(),
                Instant.now());
    }
}
