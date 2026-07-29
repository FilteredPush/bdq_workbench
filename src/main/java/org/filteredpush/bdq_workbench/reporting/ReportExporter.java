package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;

/** Extension point for output formats. */
public interface ReportExporter {
    String format();

    void export(ExecutionSummary summary, OutputStream outputStream) throws IOException;
}
