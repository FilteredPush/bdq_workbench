/** UnresolvedResponsesExporter.java
 *
 * Exports, as a small standalone spreadsheet, the responses that don't apply to a single real record (built-in multi-record measures and synthesized unresolved/unbound placeholders).
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

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;

/**
 * Exports responses whose record ID is one of the sentinel values {@code "MULTIRECORD"}
 * (built-in multi-record measures) or {@code "*"} (synthesized unresolved/unbound placeholders)
 * as their own small workbook, since they don't correspond to a single real record and
 * {@link org.datakurator.postprocess.XLSXPostProcessor}'s per-record model (used by
 * {@link XlsxReportExporter}) has no place for them.
 *
 * <p>This is deliberately a separate file rather than an extra sheet appended to
 * {@link XlsxReportExporter}'s output: this exporter always builds a small, fresh {@link
 * XSSFWorkbook} directly (proportional to the number of tests, not the number of records), so it
 * never needs to read a large existing workbook back into memory the way appending a sheet to an
 * already-written file would — which, for a large enough dataset, can exceed Apache POI's
 * single-zip-entry read cap when reopening the file.
 *
 * <p>Registered under format {@code "xls-unresolved"}, written as an Office Open XML workbook to
 * {@code bdq-report-xls-unresolved.xlsx} (see {@link #fileExtension()}).
 */
public class UnresolvedResponsesExporter implements ReportExporter {

    private static final Set<String> SENTINEL_RECORD_IDS = Set.of("MULTIRECORD", "*");
    private static final String SHEET_NAME = "Unresolved & Multi-record";
    private static final String[] COLUMNS = {"Record Id", "Test Id", "Phase", "Response Status", "Response Result", "Comment"};

    /**
     * @return {@code "xls-unresolved"}, the format identifier for this exporter
     */
    @Override
    public String format() {
        return "xls-unresolved";
    }

    /**
     * @return {@code "xlsx"}, since this exporter writes an Office Open XML workbook
     */
    @Override
    public String fileExtension() {
        return "xlsx";
    }

    /**
     * Writes a single-sheet workbook listing every sentinel-record response in {@code summary}
     * (just a header row if there are none).
     *
     * @param summary the execution summary whose sentinel-record responses are exported
     * @param outputStream the stream to write the XLSX workbook to; not closed by this method
     * @throws IOException if writing to {@code outputStream} fails
     */
    @Override
    public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
        List<Response> sentinelResponses = summary.responses().stream()
                .filter(response -> SENTINEL_RECORD_IDS.contains(response.recordId()))
                .toList();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            Row header = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                header.createCell(i).setCellValue(COLUMNS[i]);
            }
            int rowNum = 1;
            for (Response response : sentinelResponses) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(response.recordId());
                row.createCell(1).setCellValue(response.testId());
                row.createCell(2).setCellValue(response.phase() == null ? "" : response.phase().name());
                row.createCell(3).setCellValue(defaulted(response.responseStatus()));
                row.createCell(4).setCellValue(defaulted(response.responseResult()));
                String comment = firstNonBlank(response.comment(), response.message());
                row.createCell(5).setCellValue(comment == null ? "" : comment);
            }
            workbook.write(outputStream);
        }
    }

    private static String defaulted(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
