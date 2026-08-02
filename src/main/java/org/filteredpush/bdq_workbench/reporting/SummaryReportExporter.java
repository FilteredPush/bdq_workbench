package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

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
        appendMeasureSection(builder, "Multi-record COUNT measures", summary, BuiltInMeasureSpec.MeasureKind.COUNT);
        appendMeasureSection(builder, "Multi-record QA measures", summary, BuiltInMeasureSpec.MeasureKind.QA);
        outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendMeasureSection(
            StringBuilder builder,
            String title,
            ExecutionSummary summary,
            BuiltInMeasureSpec.MeasureKind kind) {
        builder.append(title).append(":\n");
        Map<String, Map<Phase, Response>> byTest = summary.multiRecordMeasureResponsesByTestAndPhase();
        if (byTest.isEmpty()) {
            builder.append(" - none\n");
            return;
        }
        boolean any = false;
        for (Map<Phase, Response> byPhase : byTest.values()) {
            Response example = byPhase.values().stream().findFirst().orElse(null);
            if (example == null || !kind.name().equals(example.parameters().get(BuiltInMeasureSpec.KIND_KEY))) {
                continue;
            }
            any = true;
            String label = example.parameters().getOrDefault(BuiltInMeasureSpec.MEASURE_LABEL_KEY, example.testId());
            builder.append(" - ").append(label).append('\n');
            builder.append("   Pre-amendment: ").append(renderPhaseValue(byPhase.get(Phase.PRE_AMENDMENT), kind)).append('\n');
            builder.append("   Post-amendment: ").append(renderPhaseValue(byPhase.get(Phase.POST_AMENDMENT), kind)).append('\n');
        }
        if (!any) {
            builder.append(" - none\n");
        }
    }

    private static String renderPhaseValue(Response response, BuiltInMeasureSpec.MeasureKind kind) {
        if (response == null) {
            return "not run";
        }
        if (kind == BuiltInMeasureSpec.MeasureKind.COUNT) {
            String count = response.parameters().getOrDefault(BuiltInMeasureSpec.MATCHING_COUNT_KEY, response.responseResult());
            String total = response.parameters().getOrDefault(BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "?");
            String percentage = response.parameters().get(BuiltInMeasureSpec.PERCENTAGE_KEY);
            return percentage == null || percentage.isBlank()
                    ? count + "/" + total
                    : count + "/" + total + " (" + percentage + "%)";
        }
        return response.responseResult() == null || response.responseResult().isBlank()
                ? "no result"
                : response.responseResult();
    }
}
