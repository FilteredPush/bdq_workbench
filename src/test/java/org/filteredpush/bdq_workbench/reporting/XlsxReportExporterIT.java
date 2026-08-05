/** XlsxReportExporterIT.java
 *
 * End-to-end integration test exercising XlsxReportExporter through a full WorkbenchFacade run.
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

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.filteredpush.bdq_workbench.app.AppConfig;
import org.filteredpush.bdq_workbench.app.WorkbenchFacade;
import org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionService;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;
import org.filteredpush.bdq_workbench.test_discovery.DefaultTestBindingService;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.filteredpush.bdq_workbench.test_discovery.TestDiscoveryService;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test: runs a full {@link WorkbenchFacade} pipeline against the same
 * fixture use case/RDF definitions/dataset as {@code WorkbenchFacadeIT}, with {@link
 * XlsxReportExporter} wired into the {@link ReportingService}, and asserts that the resulting
 * {@code reports/bdq-report-xls.xlsx} file is a valid, readable workbook with the expected
 * per-record sheets.
 */
class XlsxReportExporterIT {

    @Test
    void runsPipelineAndWritesAReadableXlsxReport() throws Exception {
        Path base = Path.of("src", "test", "resources", "integration");
        var resolver = new RdfPolicyResolverService(base.resolve("bdquc.xml"), List.of(base.resolve("bdqtest.ttl")));
        TestDiscoveryService discovery = () -> {
            try {
                Method m = StubImpl.class.getMethod("validate", Map.class);
                return List.of(new DiscoveredImplementation(
                        "urn:test:validate",
                        null,
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        StubImpl.class.getName(),
                        "validate",
                        null,
                        List.of(new MethodParameter(0, "record", ParameterRole.LEGACY_RECORD, "record", Map.class.getName(), true)),
                        new StubImpl(),
                        m));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        };
        WorkbenchFacade facade = new WorkbenchFacade(
                new DefaultIngestService(),
                resolver,
                discovery,
                new DefaultTestBindingService(),
                new ParallelPhaseExecutionService(1, new ReflectionExecutionAdapter()),
                new ReportingService(List.of(new XlsxReportExporter())));

        ExecutionSummary summary = facade.run(new AppConfig(
                base.resolve("bdquc.xml"),
                List.of(base.resolve("bdqtest.ttl")),
                base.resolve("dataset.zip"),
                "uc1",
                List.of("org.filteredpush"),
                1));

        assertThat(summary.responses()).isNotEmpty();

        Path xlsxPath = Path.of("reports", "bdq-report-xls.xlsx");
        assertThat(Files.exists(xlsxPath)).as("XLSX report was written to %s", xlsxPath).isTrue();

		try (var in = Files.newInputStream(xlsxPath); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Sheet validations = workbook.getSheet("Validations");
            assertThat(validations).as("Validations sheet").isNotNull();

            boolean foundRecordRow = false;
            for (int r = 1; r <= validations.getLastRowNum(); r++) {
                Row row = validations.getRow(r);
                if (row != null && "occ-1".equals(cellString(row, 0))) {
                    foundRecordRow = true;
                }
            }
            assertThat(foundRecordRow).as("found a Validations row for record occ-1").isTrue();
        }
    }

    private static String cellString(Row row, int column) {
        if (row == null || row.getCell(column) == null) {
            return "";
        }
        return row.getCell(column).getStringCellValue();
    }

    public static class StubImpl {
        public StubDQResponse validate(Map<String, String> record) {
            return new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", record.get("occurrenceID"));
        }
    }

    public static class StubDQResponse {
        private final StubResultState resultState;
        private final StubResultValue value;
        private final String comment;

        StubDQResponse(String status, Object value, String comment) {
            this.resultState = new StubResultState(status);
            this.value = new StubResultValue(value);
            this.comment = comment;
        }

        public StubResultState getResultState() {
            return resultState;
        }

        public StubResultValue getValue() {
            return value;
        }

        public String getComment() {
            return comment;
        }
    }

    public static class StubResultState {
        private final String label;

        StubResultState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static class StubResultValue {
        private final Object object;

        StubResultValue(Object object) {
            this.object = object;
        }

        public Object getObject() {
            return object;
        }
    }
}
