package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;

/** Exports the normalized response stream for downstream processing. */
public class DetailedResponseStreamExporter implements ReportExporter {

    @Override
    public String format() {
        return "responses";
    }

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

    private static String value(String raw) {
        return raw == null ? "" : raw.replace('\t', ' ').replace('\n', ' ');
    }
}
