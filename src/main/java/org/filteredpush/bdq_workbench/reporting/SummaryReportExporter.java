/** SummaryReportExporter.java
 *
 * Exports a concise, human-readable summary report of execution outcomes, counts, and top amendment values.
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

/**
 * Exports a concise, human-readable summary report of execution outcomes by status.
 *
 * <p>Rendering is delegated to the public {@link #renderSummaryText(String, ExecutionSummary)}
 * method, which is also usable directly by callers (such as a UI) that want the same report
 * text without going through {@link ReportExporter#export(ExecutionSummary, OutputStream)}. The
 * rendered report combines: the run's execution context (use case identity, input file, and
 * Darwin Core term/record counts, drawn from
 * {@link org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata}); response counts broken
 * down by {@link Phase}, by {@code response.status}, and by {@code response.result}; the
 * pre-amendment and post-amendment values of any built-in multi-record
 * {@link BuiltInMeasureSpec.MeasureKind#COUNT} and {@link BuiltInMeasureSpec.MeasureKind#QA}
 * measures; and the ten most frequent filled-in amendment values and amended
 * original-to-proposed value pairs. Registered under format {@code "summary"}.
 */
public class SummaryReportExporter implements ReportExporter {

    /**
     * @return {@code "summary"}, the format identifier for this exporter
     */
    @Override
    public String format() {
        return "summary";
    }

    /**
     * Writes the rendered summary report, titled {@code "BDQ Workbench Summary"}, to the given
     * output stream as UTF-8 text.
     *
     * @param summary the execution summary to render and export
     * @param outputStream the stream to write the rendered report to; not closed by this method
     * @throws IOException if writing to {@code outputStream} fails
     */
    @Override
    public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
        outputStream.write(renderSummaryText("BDQ Workbench Summary", summary)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Renders a full summary report for the given execution summary under the given title.
     *
     * <p>The rendered text includes, in order: the run's execution context; an explanation of
     * how the counts below are derived from the normalized response stream; counts by phase, by
     * response status, and by response result; the pre-amendment and post-amendment values of
     * any built-in multi-record COUNT and QA measures; and the ten most frequent filled-in
     * amendment values and amended original-to-proposed value pairs.
     *
     * @param title the heading line for the report (e.g. {@code "BDQ Workbench Summary"})
     * @param executionSummary the execution summary (responses and metadata) to summarize
     * @return the rendered, multi-line summary report text
     */
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

    /**
     * Appends the run's execution context — use case identity, input file path, and the
     * dataset's Darwin Core term and record counts — to the report.
     *
     * @param builder the report being built; appended to in place
     * @param metadata the execution summary metadata to describe
     */
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

    /**
     * Renders a use case's identity for display, combining its ID and label when both are
     * present and falling back to whichever one is available, or {@code "<unknown>"} if
     * neither is.
     *
     * @param metadata the execution summary metadata carrying the use case identity
     * @return a display string for the use case
     */
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

    /**
     * Appends a short explanation of how the phase/status/result counts that follow in the
     * report are derived from the normalized response stream.
     *
     * @param builder the report being built; appended to in place
     */
    private static void appendCountExplanation(StringBuilder builder) {
        builder.append("Counts represent normalized BDQ response rows emitted during execution:\n")
                .append(" - By phase counts all response rows produced in each execution phase.\n")
                .append(" - By response status counts the vocabulary response.status values across all phases.\n")
                .append(" - By response result counts the vocabulary response.result values across all phases.\n")
                .append(" - Single-record tests contribute one response row per record; multi-record measures contribute one row per phase.\n");
    }

    /**
     * Appends response counts grouped by {@link Phase} to the report, listing
     * {@link Phase#PRE_AMENDMENT}, {@link Phase#AMENDMENT}, and {@link Phase#POST_AMENDMENT} in
     * that fixed order first (when present), followed by any other phases in alphabetical
     * order.
     *
     * @param builder the report being built; appended to in place
     * @param counts response counts keyed by phase
     */
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

    /**
     * Appends a titled section listing every entry of the given counts map, sorted by count
     * descending and then by key alphabetically (case-insensitive).
     *
     * @param builder the report being built; appended to in place
     * @param title the section heading
     * @param counts counts keyed by the value being tallied (e.g. a response status or result)
     */
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

    /**
     * Appends a titled section reporting the pre-amendment and post-amendment values of every
     * built-in multi-record measure of the given {@link BuiltInMeasureSpec.MeasureKind}, one
     * line of "before" and "after" values per measure. Measures of the other kind, and any
     * multi-record test that is not a recognized built-in measure, are skipped.
     *
     * @param builder the report being built; appended to in place
     * @param title the section heading
     * @param summary the aggregated result summary supplying the multi-record measure responses
     * @param kind which kind of built-in measure ({@code COUNT} or {@code QA}) to include
     */
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

    /**
     * Appends a titled section listing the ten highest-count entries of the given counts map,
     * sorted by count descending and then by key alphabetically (case-insensitive).
     *
     * @param builder the report being built; appended to in place
     * @param title the section heading
     * @param counts counts keyed by the value being tallied (e.g. a {@code term=value} pair)
     */
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

    /**
     * Renders a single measure phase's response for display: for a {@code COUNT} measure, the
     * matching-count over total-records ratio (with a percentage suffix when available); for a
     * {@code QA} measure, the response result; or a placeholder if the phase was not run or
     * produced no result.
     *
     * @param response the response for this measure and phase, or {@code null} if not run
     * @param kind which kind of built-in measure the response represents
     * @return a short display string for the phase's outcome
     */
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
