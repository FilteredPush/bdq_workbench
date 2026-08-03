/** XlsCompatibilityExporter.java
 *
 * Placeholder exporter that stands in for a future kurator-ffdq-compatible spreadsheet export, currently emitting a tab-delimited text stub rather than an actual spreadsheet.
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
import java.nio.charset.StandardCharsets;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;

/**
 * Compatibility hook for kurator-ffdq spreadsheet export integration.
 *
 * <p>This is a placeholder implementation standing in for a future kurator-ffdq-compatible
 * spreadsheet (XLS) export: it does not produce an actual spreadsheet file, but instead writes
 * a tab-delimited text stub — a {@code "kurator-ffdq exporter hook"} marker line followed by
 * one line per {@link Response} with {@code recordId}, {@code testId}, {@code phase},
 * {@code responseStatus}, {@code responseResult}, and {@code comment} (blank if {@code null}) —
 * so downstream tooling can be wired to the {@code "xls-hook"} format ahead of a full
 * implementation.
 */
public class XlsCompatibilityExporter implements ReportExporter {

    /**
     * @return {@code "xls-hook"}, the format identifier for this exporter
     */
    @Override
    public String format() {
        return "xls-hook";
    }

    /**
     * Writes the tab-delimited placeholder stream — a marker line followed by one line per
     * response — to the given output stream as UTF-8 text.
     *
     * @param summary the execution summary whose responses are exported
     * @param outputStream the stream to write the placeholder text to; not closed by this
     *     method
     * @throws IOException if writing to {@code outputStream} fails
     */
    @Override
    public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
        StringBuilder builder = new StringBuilder("kurator-ffdq exporter hook\n");
        for (Response response : summary.responses()) {
            builder.append(response.recordId()).append('\t')
                    .append(response.testId()).append('\t')
                    .append(response.phase()).append('\t')
                    .append(response.responseStatus()).append('\t')
                    .append(response.responseResult()).append('\t')
                    .append(response.comment() == null ? "" : response.comment())
                    .append('\n');
        }
        outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }
}
