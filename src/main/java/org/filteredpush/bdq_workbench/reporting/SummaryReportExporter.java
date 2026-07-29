package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;

/** Exports a concise summary report of outcomes by status. */
public class SummaryReportExporter implements ReportExporter {

    @Override
    public String format() {
        return "summary";
    }

    @Override
    public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
        Map<OutcomeStatus, Long> counts = summary.responses().stream()
                .collect(Collectors.groupingBy(org.filteredpush.bdq_workbench.model.Response::status, Collectors.counting()));
        StringBuilder builder = new StringBuilder("BDQ Workbench Summary\n");
        for (OutcomeStatus status : OutcomeStatus.values()) {
            builder.append(status.name()).append(':').append(' ').append(counts.getOrDefault(status, 0L)).append('\n');
        }
        outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }
}
