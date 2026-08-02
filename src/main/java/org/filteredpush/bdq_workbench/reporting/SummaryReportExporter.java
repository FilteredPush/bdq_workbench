package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;

/** Exports a concise summary report of outcomes by status. */
public class SummaryReportExporter implements ReportExporter {

    @Override
    public String format() {
        return "summary";
    }

    @Override
    public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
        StringBuilder builder = new StringBuilder("BDQ Workbench Summary\n");
        builder.append("By phase: ").append(summary.countsByPhase()).append('\n');
        builder.append("By response status: ").append(summary.countsByResponseStatus()).append('\n');
        builder.append("By response result: ").append(summary.countsByResponseResult()).append('\n');
        outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }
}
