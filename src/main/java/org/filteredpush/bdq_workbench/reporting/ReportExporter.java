/** ReportExporter.java
 *
 * Extension point (SPI) implemented by each report output format the workbench can export an execution summary to.
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
import org.filteredpush.bdq_workbench.model.ExecutionSummary;

/**
 * Extension point for output formats that render an {@link ExecutionSummary} to a stream.
 *
 * <p>Implementations are collected into a {@link java.util.List} held by {@link ReportingService}
 * and invoked by {@link ReportingService#export(ExecutionSummary)}, one output file per
 * implementation, using {@link #format()} and {@link #fileExtension()} to name the resulting file
 * and {@link #export(ExecutionSummary, OutputStream)} to write its content. Current implementations
 * are {@link DetailedResponseStreamExporter} (a tab-delimited dump of every response),
 * {@link SummaryReportExporter} (a human-readable aggregate report),
 * {@link XlsxReportExporter} (an XLSX spreadsheet via kurator-ffdq's {@code XLSXPostProcessor}),
 * and {@link RdfResponseExporter} (an RDF {@code bdqffdq:DataQualityReport}).
 */
public interface ReportExporter {

    /**
     * Identifies this export format, used by {@link ReportingService} to name the output file
     * it writes (e.g. {@code "summary"} produces {@code bdq-report-summary.txt}).
     *
     * @return a short, filesystem-safe identifier for this format
     */
    String format();

    /**
     * Identifies the file extension {@link ReportingService} should use for the file this
     * exporter writes (e.g. {@code "txt"} for {@code bdq-report-summary.txt}).
     *
     * @return the file extension, without a leading dot; defaults to {@code "txt"}
     */
    default String fileExtension() {
        return "txt";
    }

    /**
     * Renders the given execution summary and writes it to the output stream.
     *
     * @param summary the execution summary (responses and aggregated metadata) to export
     * @param outputStream the stream to write the rendered report to; not closed by this method
     * @throws IOException if writing to {@code outputStream} fails
     */
    void export(ExecutionSummary summary, OutputStream outputStream) throws IOException;
}
