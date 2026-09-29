/** StructuredHtmlReportExporter.java
 *
 * Exports a human-readable HTML report for flat and structured per-record BDQ responses.
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SubjectRef;

/**
 * Exports a human-readable HTML report for flat and structured results.
 *
 * <p>The rendered report begins with run metadata, an overview of the input data view (a diagram
 * of the input tables, their relationships, and how the execution view was built from them —
 * flattened, or with related-row multiplicity retained — plus a table of record counts and the
 * tables ignored for lacking test bindings), a ranked "high-impact action items" summary,
 * and one comparative visualization of any emitted multi-record measures before the per-record
 * sections. For flat runs, each test group renders as a simplified single-level summary. For
 * structured runs, each test group renders a core-record-level summary followed by nested detail
 * assertions, including source-row selectors derived from each contributing {@link SubjectRef}.
 * The exporter complements the existing summary, tab-delimited, RDF, XLSX, and Markdown outputs
 * rather than replacing them.
 */
public class StructuredHtmlReportExporter implements ReportExporter {

	private static final String MULTIRECORD_SENTINEL = "MULTIRECORD";
	private static final String UNRESOLVED_SENTINEL = "*";
	/** The most bound terms named per table in the input-view overview. */
	private static final int MAX_LISTED_TERMS = 6;

	/**
	 * @return {@code "structured-html"}, the format identifier for this exporter
	 */
	@Override
	public String format() {
		return "structured-html";
	}

	/**
	 * @return {@code "html"}, since this exporter writes HTML
	 */
	@Override
	public String fileExtension() {
		return "html";
	}

	/**
	 * Writes a structured HTML report to the given output stream as UTF-8 text.
	 *
	 * @param summary the execution summary whose responses should be rendered
	 * @param outputStream the stream to write the HTML report to; not closed by this method
	 * @throws IOException if writing to {@code outputStream} fails
	 */
	@Override
	public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
		outputStream.write(renderHtml(summary).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Renders a structured HTML report for the supplied execution summary.
	 *
	 * @param summary the execution summary whose responses should be rendered
	 * @return the rendered HTML report
	 */
	public static String renderHtml(ExecutionSummary summary) {
		StructuredReportInsights insights = StructuredReportInsights.from(summary);
		Map<String, CanonicalRecord> recordsById = summary.dataset().records().stream()
				.collect(Collectors.toMap(CanonicalRecord::id, record -> record, (left, right) -> left, LinkedHashMap::new));
		Map<String, List<Response>> responsesByRecord = new LinkedHashMap<>();
		for (String recordId : orderedRecordIds(summary, recordsById.keySet())) {
			responsesByRecord.put(recordId, new ArrayList<>());
		}
		List<Response> aggregateResponses = new ArrayList<>();
		for (Response response : summary.responses()) {
			if (isPerRecordResponse(response)) {
				responsesByRecord.computeIfAbsent(response.recordId(), ignored -> new ArrayList<>()).add(response);
			} else {
				aggregateResponses.add(response);
			}
		}

		StringBuilder builder = new StringBuilder()
				.append("<!DOCTYPE html>\n")
				.append("<html lang=\"en\">\n")
				.append("<head>\n")
				.append("  <meta charset=\"UTF-8\">\n")
				.append("  <title>BDQ Workbench Structured Report</title>\n")
				.append("  <style>\n")
				.append("    body { font-family: sans-serif; line-height: 1.5; margin: 2rem; }\n")
				.append("    code { background: #f4f4f4; padding: 0.1rem 0.25rem; }\n")
				.append("    section { margin-bottom: 2rem; }\n")
				.append("    ul { margin-top: 0.5rem; }\n")
				.append("    details { margin-top: 0.75rem; }\n")
				.append("    table { border-collapse: collapse; width: 100%; margin-top: 0.75rem; }\n")
				.append("    th, td { border: 1px solid #d0d7de; padding: 0.5rem; text-align: left; vertical-align: top; }\n")
				.append("    th { background: #f6f8fa; }\n")
				.append("    .measure-track { width: 100%; min-width: 12rem; background: #edf2f7; border-radius: 999px; overflow: hidden; margin-top: 0.25rem; }\n")
				.append("    .measure-fill { height: 0.9rem; }\n")
				.append("    .measure-fill.pre { background: #6a1b9a; }\n")
				.append("    .measure-fill.post { background: #00897b; }\n")
				.append("    .measure-value { display: block; font-weight: 600; }\n")
				.append("    .measure-change.positive { color: #0b6e4f; }\n")
				.append("    .measure-change.negative { color: #b42318; }\n")
				.append("    .measure-change.neutral { color: #57606a; }\n")
				.append(InputViewDiagram.CSS)
				.append("  </style>\n")
				.append("</head>\n")
				.append("<body>\n")
				.append("<h1>BDQ Workbench Structured Report</h1>\n");
		appendRunMetadata(builder, summary, insights);
		appendInputView(builder, summary, InputViewOverview.from(summary));
		appendHighImpactActionItems(builder, insights);
		appendMeasureDifferenceVisualization(builder, summary);
		for (Map.Entry<String, List<Response>> entry : responsesByRecord.entrySet()) {
			appendRecordSection(builder, entry.getKey(), entry.getValue(), recordsById.get(entry.getKey()));
		}
		appendAggregateSection(builder, aggregateResponses);
		builder.append("</body>\n</html>\n");
		return builder.toString();
	}

	/**
	 * Appends run metadata to the report preamble.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary carrying run metadata
	 * @param insights derived timing and impact metadata for the run
	 */
	private static void appendRunMetadata(
			StringBuilder builder,
			ExecutionSummary summary,
			StructuredReportInsights insights) {
		builder.append("<section>\n")
				.append("  <h2>Run metadata</h2>\n")
				.append("  <ul>\n")
				.append("    <li><strong>Use case:</strong> ")
				.append(escapeHtml(describeUseCase(summary)))
				.append("</li>\n")
				.append("    <li><strong>Input file:</strong> ")
				.append(escapeHtml(summary.metadata().inputFile().isBlank() ? "<unknown>" : summary.metadata().inputFile()))
				.append("</li>\n")
				.append("    <li><strong>Run started:</strong> ")
				.append(escapeHtml(formatInstant(insights.runStartedAt())))
				.append("</li>\n")
				.append("    <li><strong>Run finished:</strong> ")
				.append(escapeHtml(formatInstant(insights.runFinishedAt())))
				.append("</li>\n")
				.append("    <li><strong>Records selected for execution:</strong> ")
				.append(summary.metadata().filteredSingleRecordCount())
				.append(" of ")
				.append(summary.metadata().inputSingleRecordCount())
				.append("</li>\n")
				.append("    <li><strong>Darwin Core terms selected for execution:</strong> ")
				.append(summary.metadata().filteredDarwinCoreTermCount())
				.append(" of ")
				.append(summary.metadata().inputDarwinCoreTermCount())
				.append("</li>\n")
				.append("    <li><strong>Record filters:</strong> ");
		if (summary.metadata().recordFilters().isEmpty()) {
			builder.append("none</li>\n");
		} else {
			builder.append("<ul>\n");
			summary.metadata().recordFilters().forEach((field, values) -> builder.append("      <li>")
					.append(escapeHtml(field))
					.append(" = ")
					.append(escapeHtml(String.join(" | ", values)))
					.append("</li>\n"));
			builder.append("    </ul></li>\n");
		}
		builder.append("  </ul>\n")
				.append("</section>\n");
	}

	/**
	 * Appends an overview of the input tables and the view the tests ran over: a description of
	 * the view, a diagram of the tables and view construction, and a per-table summary.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary carrying record-selection metadata
	 * @param overview the input-view overview to render
	 */
	private static void appendInputView(StringBuilder builder, ExecutionSummary summary, InputViewOverview overview) {
		builder.append("<section>\n")
				.append("  <h2>Input data view</h2>\n");
		if (!overview.isKnown()) {
			builder.append("  <p><em>The view used to run the tests was not recorded by ingest.</em></p>\n")
					.append("</section>\n");
			return;
		}
		DatasetInputDescription description = overview.description();
		builder.append("  <p><strong>")
				.append(escapeHtml(overview.modeLabel()))
				.append(".</strong> ")
				.append(escapeHtml(overview.modeExplanation()))
				.append("</p>\n")
				.append("  <ul>\n");
		if (!description.viewSource().isBlank()) {
			builder.append("    <li><strong>View source:</strong> ")
					.append(escapeHtml(description.viewSource()))
					.append("</li>\n");
		}
		builder.append("    <li><strong>Grain table:</strong> <code>")
				.append(escapeHtml(description.grainTable()))
				.append("</code> → ")
				.append(description.viewRecordCount())
				.append(" execution record(s), ")
				.append(summary.metadata().filteredSingleRecordCount())
				.append(" selected after record filtering</li>\n")
				.append("    <li><strong>Input tables:</strong> ")
				.append(overview.tables().size())
				.append(" (")
				.append(overview.includedTables().size())
				.append(" used by the view, ")
				.append(overview.ignoredTables().size())
				.append(" ignored for lacking test bindings, ")
				.append(overview.notIncludedTables().size())
				.append(" not included)</li>\n");
		List<String> notes = overview.multiplicityNotes();
		if (!notes.isEmpty()) {
			builder.append("    <li><strong>Related-row multiplicity:</strong>\n      <ul>\n");
			notes.forEach(note -> builder.append("        <li>").append(escapeHtml(note)).append("</li>\n"));
			builder.append("      </ul>\n    </li>\n");
		}
		builder.append("  </ul>\n")
				.append(InputViewDiagram.render(overview));
		appendInputTableSummary(builder, overview);
		if (!overview.ignoredTables().isEmpty()) {
			builder.append("  <p><strong>Ignored in view construction</strong> (no test binding reads any of their terms): ")
					.append(overview.ignoredTables().stream()
							.map(table -> "<code>" + escapeHtml(table.name()) + "</code>")
							.collect(Collectors.joining(", ")))
					.append("</p>\n");
		}
		builder.append("</section>\n");
	}

	/**
	 * Appends the per-table overview grid for the input-view section.
	 *
	 * @param builder the report being built; appended to in place
	 * @param overview the input-view overview to render
	 */
	private static void appendInputTableSummary(StringBuilder builder, InputViewOverview overview) {
		builder.append("  <table>\n")
				.append("    <thead><tr><th>Table</th><th>Row type</th><th>Records</th><th>Columns</th>")
				.append("<th>Role in view</th><th>Relationship to grain</th><th>Tests reading it</th>")
				.append("<th>Bound terms supplied</th></tr></thead>\n")
				.append("    <tbody>\n");
		for (InputViewOverview.TableOverview table : overview.tables()) {
			builder.append("      <tr><td><code>")
					.append(escapeHtml(table.name()))
					.append("</code></td><td>")
					.append(escapeHtml(table.rowType().isBlank() ? "—" : table.rowType()))
					.append("</td><td>")
					.append(table.recordCountLabel())
					.append("</td><td>")
					.append(table.columnCount())
					.append("</td><td>")
					.append(escapeHtml(table.role().label()))
					.append("</td><td>")
					.append(escapeHtml(table.relationToGrain()))
					.append("</td><td>")
					.append(table.testCount())
					.append("</td><td>")
					.append(escapeHtml(table.suppliedTermsSummary(MAX_LISTED_TERMS)))
					.append("</td></tr>\n");
		}
		builder.append("    </tbody>\n")
				.append("  </table>\n");
	}

	/**
	 * Appends the high-impact improvement summary before record-by-record details.
	 *
	 * @param builder the report being built; appended to in place
	 * @param insights the ranked improvement cues to render
	 */
	private static void appendHighImpactActionItems(
			StringBuilder builder,
			StructuredReportInsights insights) {
		builder.append("<section>\n")
				.append("  <h2>High-impact action items</h2>\n")
				.append("  <ul>\n")
				.append("    <li><strong>Review issue findings:</strong> ")
				.append(insights.confirmedIssueCount())
				.append(" confirmed issue response(s), ")
				.append(insights.potentialIssueCount())
				.append(" potential issue response(s)</li>\n")
				.append("    <li><strong>Validation non-compliance:</strong> ")
				.append(insights.validationNonComplianceCount())
				.append(" summary finding(s) across ")
				.append(insights.recordsWithValidationNonCompliance())
				.append(" record(s)</li>\n");
		appendRankedInsightSection(
				builder,
				"Most frequent causes of validation non-compliance",
				insights.topValidationNonComplianceCauses());
		appendRankedInsightSection(
				builder,
				"Most effective amendment proposals seen on non-compliant records",
				insights.topAmendmentProposals());
		builder.append("  </ul>\n")
				.append("</section>\n");
	}

	/**
	 * Appends one ranked-insight subsection.
	 *
	 * @param builder the report being built; appended to in place
	 * @param title the subsection title
	 * @param insights the ranked insights to render
	 */
	private static void appendRankedInsightSection(
			StringBuilder builder,
			String title,
			List<StructuredReportInsights.RankedInsight> insights) {
		builder.append("    <li><strong>")
				.append(escapeHtml(title))
				.append(":</strong>");
		if (insights.isEmpty()) {
			builder.append(" none</li>\n");
			return;
		}
		builder.append("\n      <ul>\n");
		for (StructuredReportInsights.RankedInsight insight : insights) {
			builder.append("        <li>")
					.append(escapeHtml(insight.label()))
					.append(" — ")
					.append(insight.responseCount())
					.append(" response(s) across ")
					.append(insight.recordCount())
					.append(" record(s)</li>\n");
		}
		builder.append("      </ul>\n")
				.append("    </li>\n");
	}

	/**
	 * Appends one HTML visualization section comparing pre-amendment and post-amendment
	 * multi-record measures.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary supplying measure responses
	 */
	private static void appendMeasureDifferenceVisualization(StringBuilder builder, ExecutionSummary summary) {
		List<MeasureComparison> comparisons = summarizeMeasureComparisons(summary);
		if (comparisons.isEmpty()) {
			return;
		}
		builder.append("<section>\n")
				.append("  <h2>Measure differences between pre-amendment and post-amendment phases</h2>\n")
				.append("  <table>\n")
				.append("    <thead><tr><th>Measure</th><th>Pre-amendment</th><th>Post-amendment</th><th>Observed change</th></tr></thead>\n")
				.append("    <tbody>\n");
		for (MeasureComparison comparison : comparisons) {
			builder.append("      <tr>\n")
					.append("        <td>")
					.append(escapeHtml(comparison.label()))
					.append("</td>\n")
					.append("        <td>")
					.append(renderMeasurePhaseCell(comparison.preText(), comparison.prePercent(), "pre"))
					.append("</td>\n")
					.append("        <td>")
					.append(renderMeasurePhaseCell(comparison.postText(), comparison.postPercent(), "post"))
					.append("</td>\n")
					.append("        <td><span class=\"measure-change ")
					.append(changeCssClass(comparison.deltaPercent()))
					.append("\">")
					.append(escapeHtml(comparison.changeText()))
					.append("</span></td>\n")
					.append("      </tr>\n");
		}
		builder.append("    </tbody>\n")
				.append("  </table>\n")
				.append("</section>\n");
	}

	/**
	 * Summarizes multi-record measures into one pre/post comparison row per measure.
	 *
	 * @param summary the execution summary supplying multi-record measure responses
	 * @return the ordered measure comparisons to visualize
	 */
	private static List<MeasureComparison> summarizeMeasureComparisons(ExecutionSummary summary) {
		List<MeasureComparison> comparisons = new ArrayList<>();
		for (Map<Phase, Response> byPhase : summary.multiRecordMeasureResponsesByTestAndPhase().values()) {
			Response example = byPhase.values().stream().findFirst().orElse(null);
			if (example == null) {
				continue;
			}
			Response pre = byPhase.get(Phase.PRE_AMENDMENT);
			Response post = byPhase.get(Phase.POST_AMENDMENT);
			Integer prePercent = extractMeasurePercentage(pre);
			Integer postPercent = extractMeasurePercentage(post);
			comparisons.add(new MeasureComparison(
					example.parameters().getOrDefault(BuiltInMeasureSpec.MEASURE_LABEL_KEY, example.testId()),
					renderMeasurePhaseText(pre),
					prePercent,
					renderMeasurePhaseText(post),
					postPercent,
					renderMeasureChangeText(pre, post, prePercent, postPercent),
					computeDeltaPercent(prePercent, postPercent)));
		}
		comparisons.sort(java.util.Comparator.comparing(MeasureComparison::label, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(comparisons);
	}

	/**
	 * Renders one measure phase cell, including a percentage bar when a percentage is available.
	 *
	 * @param text the textual phase summary
	 * @param percent the optional percentage value
	 * @param cssVariant the CSS variant to apply to the percentage fill
	 * @return the HTML fragment for the phase cell
	 */
	private static String renderMeasurePhaseCell(String text, Integer percent, String cssVariant) {
		StringBuilder builder = new StringBuilder()
				.append("<span class=\"measure-value\">")
				.append(escapeHtml(text))
				.append("</span>");
		if (percent != null) {
			builder.append("<div class=\"measure-track\"><div class=\"measure-fill ")
					.append(cssVariant)
					.append("\" style=\"width: ")
					.append(Math.max(0, Math.min(100, percent)))
					.append("%;\"></div></div>");
		}
		return builder.toString();
	}

	/**
	 * Renders one measure phase as concise text.
	 *
	 * @param response the phase response, or {@code null} if that phase was not run
	 * @return the textual summary for the phase
	 */
	private static String renderMeasurePhaseText(Response response) {
		if (response == null) {
			return "not run";
		}
		if (BuiltInMeasureSpec.MeasureKind.COUNT.name().equals(response.parameters().get(BuiltInMeasureSpec.KIND_KEY))) {
			String count = response.parameters().getOrDefault(BuiltInMeasureSpec.MATCHING_COUNT_KEY, response.responseResult());
			String total = response.parameters().getOrDefault(BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "?");
			String percentage = response.parameters().get(BuiltInMeasureSpec.PERCENTAGE_KEY);
			return percentage == null || percentage.isBlank()
					? count + "/" + total
					: count + "/" + total + " (" + percentage + "%)";
		}
		return firstNonBlank(response.responseResult(), response.responseStatus(), "not run");
	}

	/**
	 * Extracts a whole-number percentage for a measure phase when one is available.
	 *
	 * @param response the phase response
	 * @return the rounded percentage, or {@code null} when unavailable
	 */
	private static Integer extractMeasurePercentage(Response response) {
		if (response == null) {
			return null;
		}
		String percentage = response.parameters().get(BuiltInMeasureSpec.PERCENTAGE_KEY);
		if (percentage == null || percentage.isBlank()) {
			return null;
		}
		try {
			return (int) Math.round(Double.parseDouble(percentage));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	/**
	 * Renders one concise change summary for a measure's pre/post pair.
	 *
	 * @param pre the pre-amendment response, or {@code null} if not run
	 * @param post the post-amendment response, or {@code null} if not run
	 * @param prePercent the rounded pre-amendment percentage, when available
	 * @param postPercent the rounded post-amendment percentage, when available
	 * @return the human-readable change summary
	 */
	private static String renderMeasureChangeText(
			Response pre,
			Response post,
			Integer prePercent,
			Integer postPercent) {
		if (prePercent != null && postPercent != null) {
			int delta = postPercent - prePercent;
			if (delta == 0) {
				return "No percentage-point change";
			}
			return (delta > 0 ? "+" : "") + delta + " percentage point(s)";
		}
		String preText = renderMeasurePhaseText(pre);
		String postText = renderMeasurePhaseText(post);
		return Objects.equals(preText, postText)
				? "No observed change"
				: preText + " → " + postText;
	}

	/**
	 * Computes the percentage delta for CSS styling when both phases carry percentages.
	 *
	 * @param prePercent the rounded pre-amendment percentage, when available
	 * @param postPercent the rounded post-amendment percentage, when available
	 * @return the signed delta, or {@code null} when unavailable
	 */
	private static Integer computeDeltaPercent(Integer prePercent, Integer postPercent) {
		return prePercent == null || postPercent == null ? null : postPercent - prePercent;
	}

	/**
	 * Chooses the CSS class for one change summary.
	 *
	 * @param deltaPercent the signed percentage delta, or {@code null} when unavailable
	 * @return {@code positive}, {@code negative}, or {@code neutral}
	 */
	private static String changeCssClass(Integer deltaPercent) {
		if (deltaPercent == null || deltaPercent == 0) {
			return "neutral";
		}
		return deltaPercent > 0 ? "positive" : "negative";
	}

	/**
	 * Returns the first non-blank string from the supplied candidates.
	 *
	 * @param values candidate strings in priority order
	 * @return the first non-blank value, or the empty string when none are usable
	 */
	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	/**
	 * Returns core record IDs in dataset order, followed by any response-only record IDs.
	 *
	 * @param summary the execution summary supplying response-only record IDs
	 * @param datasetRecordIds the dataset's core record IDs in encounter order
	 * @return the ordered record IDs to render
	 */
	private static List<String> orderedRecordIds(ExecutionSummary summary, Collection<String> datasetRecordIds) {
		Set<String> ordered = new LinkedHashSet<>(datasetRecordIds);
		summary.responses().stream()
				.filter(StructuredHtmlReportExporter::isPerRecordResponse)
				.map(Response::recordId)
				.forEach(ordered::add);
		return List.copyOf(ordered);
	}

	/**
	 * Appends one record section to the report.
	 *
	 * @param builder the report being built; appended to in place
	 * @param recordId the core record ID the section represents
	 * @param responses the responses for that core record
	 * @param record the corresponding canonical record, or {@code null} when unavailable
	 */
	private static void appendRecordSection(
			StringBuilder builder,
			String recordId,
			List<Response> responses,
			CanonicalRecord record) {
		builder.append("<section>\n")
				.append("  <h2>Record <code>")
				.append(escapeHtml(recordId))
				.append("</code></h2>\n");
		if (record != null && !record.terms().isEmpty()) {
			builder.append("  <p>Core terms: ")
					.append(record.terms().entrySet().stream()
							.sorted(Map.Entry.comparingByKey())
							.map(entry -> "<code>" + escapeHtml(entry.getKey()) + "=" + escapeHtml(entry.getValue()) + "</code>")
							.collect(Collectors.joining(", ")))
					.append("</p>\n");
		}
		if (responses.isEmpty()) {
			builder.append("  <p><em>No responses emitted for this record.</em></p>\n")
					.append("</section>\n");
			return;
		}
		for (Map.Entry<TestGroupKey, List<Response>> entry : groupByTest(responses).entrySet()) {
			appendTestGroup(builder, entry.getKey(), entry.getValue());
		}
		builder.append("</section>\n");
	}

	/**
	 * Groups responses by test identity while preserving encounter order.
	 *
	 * @param responses the responses to group
	 * @return responses keyed by test identity
	 */
	private static Map<TestGroupKey, List<Response>> groupByTest(List<Response> responses) {
		Map<TestGroupKey, List<Response>> grouped = new LinkedHashMap<>();
		for (Response response : responses) {
			grouped.computeIfAbsent(TestGroupKey.of(response), ignored -> new ArrayList<>()).add(response);
		}
		return grouped;
	}

	/**
	 * Appends one per-test subsection for a core record.
	 *
	 * @param builder the report being built; appended to in place
	 * @param key the grouped test identity
	 * @param responses the responses belonging to the test group
	 */
	private static void appendTestGroup(StringBuilder builder, TestGroupKey key, List<Response> responses) {
		builder.append("  <h3>")
				.append(escapeHtml(key.phase()))
				.append(" · ")
				.append(escapeHtml(key.testType()))
				.append(" · <code>")
				.append(escapeHtml(key.testId()))
				.append("</code></h3>\n");

		Response rollup = responses.stream().filter(Response::derived).findFirst().orElse(null);
		List<Response> detailResponses = responses.stream()
				.filter(response -> !response.derived())
				.filter(response -> response.subjectRef() != null)
				.toList();
		List<Response> flatResponses = responses.stream()
				.filter(response -> !response.derived())
				.filter(response -> response.subjectRef() == null)
				.toList();

		builder.append("  <ul>\n")
				.append("    <li><strong>Summary:</strong> ")
				.append(escapeHtml(summaryLine(rollup, detailResponses, flatResponses)))
				.append("</li>\n");
		if (rollup != null && !rollup.contributingSubjectRefs().isEmpty()) {
			builder.append("    <li><strong>Contributing subjects:</strong> ")
					.append(rollup.contributingSubjectRefs().stream()
							.map(StructuredSubjectSelectors::selectorLabel)
							.map(StructuredHtmlReportExporter::escapeHtml)
							.collect(Collectors.joining(", ")))
					.append("</li>\n");
		}
		if (detailResponses.isEmpty()) {
			appendFlatResponses(builder, flatResponses.isEmpty() && rollup != null ? List.of(rollup) : flatResponses);
			builder.append("  </ul>\n");
			return;
		}
		builder.append("  </ul>\n")
				.append("  <details>\n")
				.append("    <summary>")
				.append(escapeHtml(detailResponses.size() + " structured detail assertion(s)"))
				.append("</summary>\n")
				.append("    <ul>\n");
		for (Response detail : orderDetails(detailResponses, rollup)) {
			appendDetailResponse(builder, detail);
		}
		builder.append("    </ul>\n")
				.append("  </details>\n");
	}

	/**
	 * Appends one bullet per flat response.
	 *
	 * @param builder the report being built; appended to in place
	 * @param responses the flat responses to render
	 */
	private static void appendFlatResponses(StringBuilder builder, List<Response> responses) {
		for (Response response : responses) {
			builder.append("    <li>")
					.append(escapeHtml(responseLine(response)))
					.append("</li>\n");
		}
	}

	/**
	 * Orders structured detail responses using the rollup's contributing-subject order when
	 * available, falling back to the responses' existing encounter order.
	 *
	 * @param detailResponses the structured detail responses to order
	 * @param rollup the optional derived rollup that references those details
	 * @return the ordered detail responses
	 */
	private static List<Response> orderDetails(List<Response> detailResponses, Response rollup) {
		if (rollup == null || rollup.contributingSubjectRefs().isEmpty()) {
			return detailResponses;
		}
		Map<String, List<Response>> detailsBySubject = detailResponses.stream()
				.collect(Collectors.groupingBy(
						response -> response.subjectRef().sortKey(),
						LinkedHashMap::new,
						Collectors.toCollection(ArrayList::new)));
		List<Response> ordered = new ArrayList<>();
		for (SubjectRef subjectRef : rollup.contributingSubjectRefs()) {
			List<Response> matches = detailsBySubject.remove(subjectRef.sortKey());
			if (matches != null) {
				ordered.addAll(matches);
			}
		}
		detailsBySubject.values().forEach(ordered::addAll);
		return ordered;
	}

	/**
	 * Appends one structured detail response bullet.
	 *
	 * @param builder the report being built; appended to in place
	 * @param response the structured detail response to render
	 */
	private static void appendDetailResponse(StringBuilder builder, Response response) {
		builder.append("      <li><strong>Subject <code>")
				.append(escapeHtml(StructuredSubjectSelectors.subjectLabel(response.subjectRef())))
				.append("</code> at <code>")
				.append(escapeHtml(StructuredSubjectSelectors.selectorLabel(response.subjectRef())))
				.append("</code>:</strong> ")
				.append(escapeHtml(responseLine(response)))
				.append("</li>\n");
	}

	/**
	 * Appends any aggregate or unresolved responses that do not belong to a single core record.
	 *
	 * @param builder the report being built; appended to in place
	 * @param responses the aggregate or unresolved responses to render
	 */
	private static void appendAggregateSection(StringBuilder builder, List<Response> responses) {
		if (responses.isEmpty()) {
			return;
		}
		builder.append("<section>\n")
				.append("  <h2>Aggregate and unresolved responses</h2>\n")
				.append("  <ul>\n");
		for (Response response : responses) {
			builder.append("    <li><code>")
					.append(escapeHtml(response.recordId()))
					.append("</code> · <code>")
					.append(escapeHtml(response.testId()))
					.append("</code> · ")
					.append(escapeHtml(responseLine(response)))
					.append("</li>\n");
		}
		builder.append("  </ul>\n")
				.append("</section>\n");
	}

	/**
	 * Renders a concise one-line summary for a grouped test section.
	 *
	 * @param rollup the optional derived rollup response
	 * @param detailResponses the structured detail responses in the group
	 * @param flatResponses the flat responses in the group
	 * @return the human-readable summary line
	 */
	private static String summaryLine(Response rollup, List<Response> detailResponses, List<Response> flatResponses) {
		if (rollup != null) {
			return responseLine(rollup);
		}
		if (!flatResponses.isEmpty()) {
			return responseLine(flatResponses.get(0));
		}
		return detailResponses.size() + " detail response(s); results: "
				+ detailResponses.stream()
						.collect(Collectors.groupingBy(
								response -> Objects.toString(response.responseResult(), "<none>"),
								LinkedHashMap::new,
								Collectors.counting()))
						.entrySet().stream()
						.sorted(Map.Entry.comparingByKey())
						.map(entry -> entry.getKey() + " × " + entry.getValue())
						.collect(Collectors.joining(", "));
	}

	/**
	 * Renders one response as a concise status/result/comment string.
	 *
	 * @param response the response to summarize
	 * @return the rendered response line
	 */
	private static String responseLine(Response response) {
		StringBuilder builder = new StringBuilder();
		builder.append(nullSafe(response.responseStatus()));
		if (response.responseResult() != null && !response.responseResult().isBlank()) {
			builder.append(" / ").append(response.responseResult());
		}
		String comment = response.comment() != null && !response.comment().isBlank()
				? response.comment()
				: response.message();
		if (comment != null && !comment.isBlank()) {
			builder.append(" — ").append(comment);
		}
		return builder.toString();
	}

	/**
	 * Reports whether a response belongs to a concrete core record section.
	 *
	 * @param response the response to inspect
	 * @return {@code true} when the response belongs to one core record
	 */
	private static boolean isPerRecordResponse(Response response) {
		return response.recordId() != null
				&& !response.recordId().isBlank()
				&& !MULTIRECORD_SENTINEL.equals(response.recordId())
				&& !UNRESOLVED_SENTINEL.equals(response.recordId());
	}

	/**
	 * Renders the use case identity for the report preamble.
	 *
	 * @param summary the execution summary carrying run metadata
	 * @return the use case display string
	 */
	private static String describeUseCase(ExecutionSummary summary) {
		boolean hasId = summary.metadata().useCaseId() != null && !summary.metadata().useCaseId().isBlank();
		boolean hasLabel = summary.metadata().useCaseLabel() != null && !summary.metadata().useCaseLabel().isBlank();
		if (hasId && hasLabel) {
			return summary.metadata().useCaseId() + " (" + summary.metadata().useCaseLabel() + ")";
		}
		if (hasId) {
			return summary.metadata().useCaseId();
		}
		if (hasLabel) {
			return summary.metadata().useCaseLabel();
		}
		return "<unknown>";
	}

	/**
	 * Formats a run timestamp for display.
	 *
	 * @param instant the timestamp to render
	 * @return the ISO-8601 timestamp, or {@code "<unknown>"} when unavailable
	 */
	private static String formatInstant(Instant instant) {
		return instant == null ? "<unknown>" : instant.toString();
	}

	/**
	 * Escapes HTML-significant characters used in the report's inline content.
	 *
	 * @param raw the raw text to escape
	 * @return the escaped text
	 */
	private static String escapeHtml(String raw) {
		return nullSafe(raw)
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
	}

	/**
	 * Substitutes an empty string for a null value.
	 *
	 * @param raw the raw string, possibly null
	 * @return the original value or an empty string
	 */
	private static String nullSafe(String raw) {
		return raw == null ? "" : raw;
	}

	/**
	 * One pre/post comparison row for the measure-difference visualization.
	 *
	 * @param label the rendered measure label
	 * @param preText the textual pre-amendment summary
	 * @param prePercent the rounded pre-amendment percentage, when available
	 * @param postText the textual post-amendment summary
	 * @param postPercent the rounded post-amendment percentage, when available
	 * @param changeText the rendered change summary
	 * @param deltaPercent the signed percentage delta, when available
	 */
	private record MeasureComparison(
			String label,
			String preText,
			Integer prePercent,
			String postText,
			Integer postPercent,
			String changeText,
			Integer deltaPercent) {
	}

	/**
	 * Identity of one rendered per-test subsection.
	 *
	 * @param testId the test identifier
	 * @param testType the test type name
	 * @param phase the phase name
	 */
	private record TestGroupKey(String testId, String testType, String phase) {
		private static TestGroupKey of(Response response) {
			return new TestGroupKey(
					response.testId(),
					response.testType() == null ? "" : response.testType().name(),
					response.phase() == null ? "" : response.phase().name());
		}
	}
}
