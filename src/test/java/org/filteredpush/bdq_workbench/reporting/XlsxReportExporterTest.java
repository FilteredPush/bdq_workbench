/** XlsxReportExporterTest.java
 *
 * Copyright 2026 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class XlsxReportExporterTest {

    private static final String VALIDATION_TEST = "urn:test:validation-country-notempty";
    private static final String MEASURE_TEST = "urn:test:measure-occurrenceid-completeness";
    private static final String AMENDMENT_TEST = "urn:test:amendment-eventdate-standardized";
    private static final String ISSUE_TEST = "urn:test:issue-country-ambiguous";

    @Test
    void formatAndFileExtensionIdentifyAnXlsxWorkbook() {
        XlsxReportExporter exporter = new XlsxReportExporter();
        assertThat(exporter.format()).isEqualTo("xls");
        assertThat(exporter.fileExtension()).isEqualTo("xlsx");
    }

    @Test
    void exportsPerRecordSheetsAndPadsMissingInformationElementsWithEmptyValues() throws Exception {
        CanonicalRecord record1 = new CanonicalRecord(
                "REC1", new LinkedHashMap<>(Map.of("occurrenceID", "REC1", "eventDate", "2023-06-15", "country", "Greenland")));
        // record2 has no "country" column at all in the input data.
        CanonicalRecord record2 = new CanonicalRecord(
                "REC2", new LinkedHashMap<>(Map.of("occurrenceID", "REC2", "eventDate", "")));

        List<ImplementationBinding> bindings = List.of(
                actedUponBinding(VALIDATION_TEST, TestType.VALIDATION, "country"),
                consultedBinding(MEASURE_TEST, TestType.MEASURE, "occurrenceID"),
                actedUponBinding(AMENDMENT_TEST, TestType.AMENDMENT, "eventDate"),
                actedUponBinding(ISSUE_TEST, TestType.ISSUE, "country"));

        List<Response> responses = List.of(
                validationResponse("REC1", "COMPLIANT"),
                validationResponse("REC2", "INTERNAL_PREREQUISITES_NOT_MET"),
                measureResponse("REC1"),
                amendmentResponse("REC1"),
                issueResponse("REC1"));

        ExecutionSummary summary = new ExecutionSummary(
                responses, null, new RecordDataset(List.of(record1, record2)), bindings);

        XlsxReportExporter exporter = new XlsxReportExporter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.export(summary, output);

        assertThat(output.size()).isGreaterThan(0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertThat(workbook.getSheet("Validations")).as("Validations sheet").isNotNull();
            assertThat(workbook.getSheet("Measures")).as("Measures sheet").isNotNull();
            assertThat(workbook.getSheet("Amendments")).as("Amendments sheet").isNotNull();
            assertThat(workbook.getSheet("Issues")).as("Issues sheet").isNotNull();
            assertThat(workbook.getSheet("Initial Values")).as("Initial Values sheet").isNotNull();
            assertThat(workbook.getSheet("Final Values")).as("Final Values sheet").isNotNull();

            Sheet validations = workbook.getSheet("Validations");
            Row header = validations.getRow(0);
            int countryColumn = columnIndexOf(header, "country");
            assertThat(countryColumn).as("country column exists even though record2 lacks it").isGreaterThanOrEqualTo(0);

            // record2's row: country was never in its input data, so it must appear padded as
            // empty rather than being missing from the sheet entirely.
            boolean foundBlankCountryForRecord2 = false;
            for (int r = 1; r <= validations.getLastRowNum(); r++) {
                Row row = validations.getRow(r);
                if (row == null) {
                    continue;
                }
                if ("REC2".equals(cellString(row, 0)) && countryColumn >= 0) {
                    assertThat(cellString(row, countryColumn)).isEqualTo("");
                    foundBlankCountryForRecord2 = true;
                }
            }
            assertThat(foundBlankCountryForRecord2).as("found a REC2 validation row with a padded blank country cell").isTrue();
        }
    }

    @Test
    void excludesSentinelResponsesFromThePerRecordWorkbook() throws Exception {
        CanonicalRecord record1 = new CanonicalRecord("REC1", Map.of("occurrenceID", "REC1"));
        List<ImplementationBinding> bindings = List.of(actedUponBinding(MEASURE_TEST, TestType.MEASURE, "occurrenceID"));

        Response boundResponse = measureResponse("REC1");
        Response multiRecordResponse = new Response(
                "MULTIRECORD", "urn:test:multi-record-measure", TestType.MEASURE,
                "org.example.BuiltIn", "count", Phase.POST_AMENDMENT, Map.of(),
                OutcomeStatus.PASSED, "RUN_HAS_RESULT", "2", "two records", null, Map.of(),
                Instant.EPOCH, Instant.EPOCH);
        Response unresolvedResponse = new Response(
                "*", "urn:test:unresolved", TestType.VALIDATION,
                null, null, Phase.PRE_AMENDMENT, Map.of(),
                OutcomeStatus.UNABLE_TO_RUN, "UNABLE_TO_RUN", null, "no implementation discovered", null, Map.of(),
                Instant.EPOCH, Instant.EPOCH);

        ExecutionSummary summary = new ExecutionSummary(
                List.of(boundResponse, multiRecordResponse, unresolvedResponse),
                null,
                new RecordDataset(List.of(record1)),
                bindings);

        XlsxReportExporter exporter = new XlsxReportExporter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.export(summary, output);

        // Sentinel responses (MULTIRECORD/*) are excluded here, not appended as an extra sheet —
        // see UnresolvedResponsesExporterTest for where they're actually reported. Excluding them
        // means this workbook never needs to be reopened/rebuilt, which for a large dataset could
        // otherwise exceed Apache POI's single-zip-entry read cap.
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertThat(workbook.getSheet("Unresolved & Multi-record")).isNull();
            assertThat(workbook.getSheet("Measures")).as("Measures sheet").isNotNull();
        }
    }

    @Test
    void handlesRunsWithNoPerRecordResponsesWithoutThrowing() throws Exception {
        CanonicalRecord record1 = new CanonicalRecord("REC1", Map.of("occurrenceID", "REC1"));
        Response unresolvedResponse = new Response(
                "*", "urn:test:unresolved", TestType.VALIDATION,
                null, null, Phase.PRE_AMENDMENT, Map.of(),
                OutcomeStatus.UNABLE_TO_RUN, "UNABLE_TO_RUN", null, "no implementation discovered", null, Map.of(),
                Instant.EPOCH, Instant.EPOCH);

        ExecutionSummary summary = new ExecutionSummary(
                List.of(unresolvedResponse), null, new RecordDataset(List.of(record1)), List.of());

        XlsxReportExporter exporter = new XlsxReportExporter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.export(summary, output);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertThat(workbook.getSheet("Summary")).isNotNull();
        }
    }

    private static ImplementationBinding actedUponBinding(String testId, TestType testType, String term) {
        MethodParameter parameter = new MethodParameter(0, "term", ParameterRole.ACTED_UPON, "dwc:" + term, "java.lang.String", true);
        BoundMethodParameter bound = new BoundMethodParameter(parameter, term, null, true, "Mapped to " + term);
        return new ImplementationBinding(
                testId, testType, "org.example.Impl", "method", Phase.PRE_AMENDMENT, Map.of(),
                BindingStatus.BOUND, ParameterizationCapability.DEFAULT_ONLY, "selected", true,
                List.of(bound), List.of());
    }

    private static ImplementationBinding consultedBinding(String testId, TestType testType, String term) {
        MethodParameter parameter = new MethodParameter(0, "term", ParameterRole.CONSULTED, "dwc:" + term, "java.lang.String", true);
        BoundMethodParameter bound = new BoundMethodParameter(parameter, term, null, true, "Mapped to " + term);
        return new ImplementationBinding(
                testId, testType, "org.example.Impl", "method", Phase.PRE_AMENDMENT, Map.of(),
                BindingStatus.BOUND, ParameterizationCapability.DEFAULT_ONLY, "selected", true,
                List.of(bound), List.of());
    }

    private static Response validationResponse(String recordId, String responseStatus) {
        return new Response(
                recordId, VALIDATION_TEST, TestType.VALIDATION, "org.example.Impl", "method", Phase.PRE_AMENDMENT,
                Map.of(), OutcomeStatus.PASSED, "RUN_HAS_RESULT".equals(responseStatus) ? responseStatus : "RUN_HAS_RESULT",
                "COMPLIANT".equals(responseStatus) ? "COMPLIANT" : null,
                "validated", null, Map.of(), Instant.EPOCH, Instant.EPOCH);
    }

    private static Response measureResponse(String recordId) {
        return new Response(
                recordId, MEASURE_TEST, TestType.MEASURE, "org.example.Impl", "method", Phase.PRE_AMENDMENT,
                Map.of(), OutcomeStatus.PASSED, "RUN_HAS_RESULT", "COMPLETE", "measured", null, Map.of(),
                Instant.EPOCH, Instant.EPOCH);
    }

    private static Response amendmentResponse(String recordId) {
        return new Response(
                recordId, AMENDMENT_TEST, TestType.AMENDMENT, "org.example.Impl", "method", Phase.AMENDMENT,
                Map.of(), OutcomeStatus.AMENDED, "FILLED_IN", null, "standardized event date", null,
                Map.of("eventDate", "2023-06-15T00:00"), Instant.EPOCH, Instant.EPOCH);
    }

    private static Response issueResponse(String recordId) {
        return new Response(
                recordId, ISSUE_TEST, TestType.ISSUE, "org.example.Impl", "method", Phase.PRE_AMENDMENT,
                Map.of(), OutcomeStatus.PASSED, "RUN_HAS_RESULT", "POTENTIAL_ISSUE", "possible issue", null,
                Map.of(), Instant.EPOCH, Instant.EPOCH);
    }

    private static int columnIndexOf(Row header, String columnName) {
        if (header == null) {
            return -1;
        }
        for (int i = 0; i < header.getLastCellNum(); i++) {
            if (header.getCell(i) != null && columnName.equals(header.getCell(i).getStringCellValue())) {
                return i;
            }
        }
        return -1;
    }

    private static String cellString(Row row, int column) {
        if (row == null || row.getCell(column) == null) {
            return "";
        }
        return row.getCell(column).getStringCellValue();
    }
}
