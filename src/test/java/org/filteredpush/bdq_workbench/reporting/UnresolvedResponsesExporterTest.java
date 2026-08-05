/** UnresolvedResponsesExporterTest.java
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
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class UnresolvedResponsesExporterTest {

    @Test
    void formatAndFileExtensionIdentifyAnXlsxWorkbook() {
        UnresolvedResponsesExporter exporter = new UnresolvedResponsesExporter();
        assertThat(exporter.format()).isEqualTo("xls-unresolved");
        assertThat(exporter.fileExtension()).isEqualTo("xlsx");
    }

    @Test
    void listsMultiRecordAndUnresolvedResponsesAndExcludesPerRecordOnes() throws Exception {
        Response perRecordResponse = new Response(
                "REC1", "urn:test:validation", TestType.VALIDATION, "org.example.Impl", "method", Phase.PRE_AMENDMENT,
                Map.of(), OutcomeStatus.PASSED, "RUN_HAS_RESULT", "COMPLIANT", "ok", null, Map.of(),
                Instant.EPOCH, Instant.EPOCH);
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
                List.of(perRecordResponse, multiRecordResponse, unresolvedResponse));

        UnresolvedResponsesExporter exporter = new UnresolvedResponsesExporter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.export(summary, output);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            Sheet sheet = workbook.getSheet("Unresolved & Multi-record");
            assertThat(sheet).as("sentinel sheet").isNotNull();
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("MULTIRECORD");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("*");
        }
    }

    @Test
    void writesJustAHeaderRowWhenThereAreNoSentinelResponses() throws Exception {
        Response perRecordResponse = new Response(
                "REC1", "urn:test:validation", TestType.VALIDATION, "org.example.Impl", "method", Phase.PRE_AMENDMENT,
                Map.of(), OutcomeStatus.PASSED, "RUN_HAS_RESULT", "COMPLIANT", "ok", null, Map.of(),
                Instant.EPOCH, Instant.EPOCH);
        ExecutionSummary summary = new ExecutionSummary(
                List.of(perRecordResponse), null, new RecordDataset(List.of()));

        UnresolvedResponsesExporter exporter = new UnresolvedResponsesExporter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.export(summary, output);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            Sheet sheet = workbook.getSheet("Unresolved & Multi-record");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getLastRowNum()).isEqualTo(0);
        }
    }
}
