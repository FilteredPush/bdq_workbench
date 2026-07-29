package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;

/** Compatibility hook for kurator-ffdq spreadsheet export integration. */
public class XlsCompatibilityExporter implements ReportExporter {

    @Override
    public String format() {
        return "xls-hook";
    }

    @Override
    public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
        outputStream.write("kurator-ffdq exporter hook\n".getBytes(StandardCharsets.UTF_8));
    }
}
