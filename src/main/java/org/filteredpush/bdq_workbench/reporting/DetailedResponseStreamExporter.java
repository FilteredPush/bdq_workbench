/** DetailedResponseStreamExporter.java
 *
 * Exports every response in an execution summary as a tab-delimited stream for downstream processing.
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
 * Exports the full, normalized response stream for downstream processing.
 *
 * <p>Writes one tab-delimited line per {@link Response} in the {@link ExecutionSummary},
 * preceded by a header line naming the columns: {@code recordId}, {@code testId},
 * {@code testType}, {@code phase}, {@code responseStatus}, {@code responseResult},
 * {@code comment}, {@code implementation} (the implementation class and method joined by
 * {@code #}), {@code parameters}, and {@code amendments} (the latter two rendered via
 * {@link java.util.Map#toString()}). Registered under format {@code "responses"}.
 */
public class DetailedResponseStreamExporter implements ReportExporter {

    /**
     * @return {@code "responses"}, the format identifier for this exporter
     */
    @Override
    public String format() {
        return "responses";
    }

    /**
     * Writes the tab-delimited response stream — a header line followed by one line per
     * response — to the given output stream as UTF-8 text.
     *
     * @param summary the execution summary whose responses are exported
     * @param outputStream the stream to write the tab-delimited text to; not closed by this
     *     method
     * @throws IOException if writing to {@code outputStream} fails
     */
    @Override
    public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("recordId\ttestId\ttestType\tphase\tresponseStatus\tresponseResult\tcomment\timplementation\tparameters\tamendments\n");
        for (Response response : summary.responses()) {
            builder.append(value(response.recordId())).append('\t')
                    .append(value(response.testId())).append('\t')
                    .append(value(response.testType() == null ? null : response.testType().name())).append('\t')
                    .append(value(response.phase() == null ? null : response.phase().name())).append('\t')
                    .append(value(response.responseStatus())).append('\t')
                    .append(value(response.responseResult())).append('\t')
                    .append(value(response.comment())).append('\t')
                    .append(value(response.implementationClass() + "#" + response.implementationMethod())).append('\t')
                    .append(value(response.parameters().toString())).append('\t')
                    .append(value(response.amendments().toString()))
                    .append('\n');
        }
        outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Renders a field value for inclusion in a tab-delimited line, substituting an empty
     * string for {@code null} and replacing any embedded tabs or newlines with spaces so the
     * line structure is preserved.
     *
     * @param raw the raw field value, possibly {@code null}
     * @return the sanitized value, safe to embed in a tab-delimited line
     */
    private static String value(String raw) {
        return raw == null ? "" : raw.replace('\t', ' ').replace('\n', ' ');
    }
}
