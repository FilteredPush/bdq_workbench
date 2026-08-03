package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.filteredpush.bdq_workbench.app.ExecutionResultSummary;
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
        outputStream.write(renderSummaryText("BDQ Workbench Summary", summary)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static String renderSummaryText(String title, ExecutionSummary executionSummary) {
        ExecutionResultSummary summary = ExecutionResultSummary.from(executionSummary);
        StringBuilder builder = new StringBuilder(title).append('\n');
        appendExecutionContext(builder, executionSummary.metadata());
        appendCountExplanation(builder);
        appendPhaseCounts(builder, summary.phaseCounts());
        appendCountSection(builder, "By response status", summary.responseStatusCounts());
        appendCountSection(builder, "By response result", summary.responseResultCounts());
        appendMeasureSection(builder, "Multi-record COUNT measures", summary, BuiltInMeasureSpec.MeasureKind.COUNT);
        appendMeasureSection(builder, "Multi-record QA measures", summary, BuiltInMeasureSpec.MeasureKind.QA);
        appendTopValuesSection(
                builder,
                "Top filled-in amendment values",
                executionSummary.metadata().filledInValueCounts());
        appendTopValuesSection(
                builder,
                "Top amended original -> proposed values",
                executionSummary.metadata().amendedValuePairCounts());
        return builder.toString();
    }

    private static void appendExecutionContext(
            StringBuilder builder,
            org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata metadata) {
        builder.append("Use case: ")
                .append(describeUseCase(metadata))
                .append('\n');
        builder.append("Input file: ")
                .append(metadata.inputFile().isBlank() ? "<unknown>" : metadata.inputFile())
                .append('\n');
        builder.append("Darwin Core terms present in input file: ")
                .append(metadata.darwinCoreTermCount())
                .append('\n');
        builder.append("SingleRecords in input file: ")
                .append(metadata.singleRecordCount())
                .append('\n');
    }

    private static String describeUseCase(org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata metadata) {
        boolean hasId = metadata.useCaseId() != null && !metadata.useCaseId().isBlank();
        boolean hasLabel = metadata.useCaseLabel() != null && !metadata.useCaseLabel().isBlank();
        if (hasId && hasLabel) {
            return metadata.useCaseId() + " (" + metadata.useCaseLabel() + ")";
        }
        if (hasId) {
            return metadata.useCaseId();
        }
        if (hasLabel) {
            return metadata.useCaseLabel();
        }
        return "<unknown>";
    }

    private static void appendCountExplanation(StringBuilder builder) {
        builder.append("Counts represent normalized BDQ response rows emitted during execution:\n")
                .append(" - By phase counts all response rows produced in each execution phase.\n")
                .append(" - By response status counts the vocabulary response.status values across all phases.\n")
                .append(" - By response result counts the vocabulary response.result values across all phases.\n")
                .append(" - Single-record tests contribute one response row per record; multi-record measures contribute one row per phase.\n");
    }

    private static void appendPhaseCounts(StringBuilder builder, Map<Phase, Long> counts) {
        builder.append("By phase:\n");
        if (counts.isEmpty()) {
            builder.append(" - none\n");
            return;
        }
        for (Phase phase : List.of(Phase.PRE_AMENDMENT, Phase.AMENDMENT, Phase.POST_AMENDMENT)) {
            Long count = counts.get(phase);
            if (count != null) {
                builder.append(" - ").append(phase).append(": ").append(count).append('\n');
            }
        }
        counts.entrySet().stream()
                .filter(entry -> !List.of(Phase.PRE_AMENDMENT, Phase.AMENDMENT, Phase.POST_AMENDMENT)
                        .contains(entry.getKey()))
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .forEach(entry -> builder.append(" - ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue())
                        .append('\n'));
    }

    private static void appendCountSection(StringBuilder builder, String title, Map<String, Long> counts) {
        builder.append(title).append(":\n");
        if (counts.isEmpty()) {
            builder.append(" - none\n");
            return;
        }
        List<Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Entry<String, Long>>comparingLong(Entry::getValue)
                .reversed()
                .thenComparing(Entry::getKey, String.CASE_INSENSITIVE_ORDER));
        entries.forEach(entry -> builder.append(" - ")
                .append(entry.getKey())
                .append(": ")
                .append(entry.getValue())
                .append('\n'));
    }

    private static void appendMeasureSection(
            StringBuilder builder,
            String title,
            ExecutionResultSummary summary,
            BuiltInMeasureSpec.MeasureKind kind) {
        builder.append(title).append(":\n");
        Map<String, Map<Phase, Response>> byTest = summary.multiRecordMeasureOutputs();
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

    private static void appendTopValuesSection(StringBuilder builder, String title, Map<String, Long> counts) {
        builder.append(title).append(":\n");
        if (counts.isEmpty()) {
            builder.append(" - none\n");
            return;
        }
        List<Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Entry<String, Long>>comparingLong(Entry::getValue)
                .reversed()
                .thenComparing(Entry::getKey, String.CASE_INSENSITIVE_ORDER));
        entries.stream()
                .limit(10)
                .forEach(entry -> builder.append(" - ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue())
                        .append('\n'));
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
