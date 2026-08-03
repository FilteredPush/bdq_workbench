package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;

/** Compatibility hook for kurator-ffdq spreadsheet export integration. */
public class XlsCompatibilityExporter implements ReportExporter {

    @Override
    public String format() {
        return "xls-hook";
    }

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
