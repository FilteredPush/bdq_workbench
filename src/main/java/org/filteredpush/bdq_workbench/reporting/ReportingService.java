package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;

/** Handles export dispatch and output locations for report formats. */
public class ReportingService {
    private final List<ReportExporter> exporters;

    public ReportingService(List<ReportExporter> exporters) {
        this.exporters = List.copyOf(exporters);
    }

    public void export(ExecutionSummary summary) {
        Path reportDir = Path.of("target", "reports");
        try {
            Files.createDirectories(reportDir);
            for (ReportExporter exporter : exporters) {
                Path outputFile = reportDir.resolve("bdq-report-" + exporter.format() + ".txt");
                try (OutputStream out = Files.newOutputStream(outputFile)) {
                    exporter.export(summary, out);
                }
            }
        } catch (IOException e) {
            throw new AppException("Unable to export reports", e);
        }
    }
}
