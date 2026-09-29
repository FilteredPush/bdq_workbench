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
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SubjectRef;

/**
 * Exports a human-readable HTML report for flat and structured results.
 *
 * <p>The rendered report begins with run metadata, an overview of the input data view (a diagram
 * of the input tables, their relationships, and how the execution view was built from them —
 * flattened, or with related-row multiplicity retained — plus a table of record counts and the
 * tables ignored for lacking test bindings), a ranked "high-impact action items" summary, and
 * comparative multi-record measure tables that separate changed pre/post results from unchanged
 * ones before the per-record sections. For flat runs, each test group renders as a simplified
 * single-level summary. For structured runs, each test group renders a core-record-level summary
 * followed by nested detail assertions, including source-row selectors derived from each
 * contributing {@link SubjectRef}. The exporter complements the existing summary, tab-delimited,
 * RDF, XLSX, and Markdown outputs rather than replacing them.
 */
public class StructuredHtmlReportExporter implements ReportExporter {

	private static final String MULTIRECORD_SENTINEL = "MULTIRECORD";
	private static final String UNRESOLVED_SENTINEL = "*";
	/** Height, in pixels, of one dumbbell-chart row's plot. */
	private static final int MEASURE_ROW_HEIGHT = 26;
	/** Radius, in pixels, of a dumbbell-chart marker. */
	private static final int MEASURE_MARKER_RADIUS = 6;
	/**
	 * Horizontal padding, in pixels, around each dumbbell plot, so a marker at 0% or 100% (radius
	 * plus stroke) stays inside the plot column instead of overlapping the neighbouring label or
	 * values.
	 */
	private static final int MEASURE_PLOT_INSET = 10;
	/** Axis tick positions, in percent, on the dumbbell chart. */
	private static final int[] MEASURE_AXIS_TICKS = {0, 25, 50, 75, 100};
	/**
	 * Styles for the pre/post measure dumbbell chart. One hue in two shades (validated as an
	 * ordinal pair): pre-amendment is a light hollow ring, post-amendment a dark filled dot, so the
	 * phases differ by shape as well as shade; the change label carries an arrow and sign, never
	 * colour alone.
	 */
	private static final String MEASURE_CHART_CSS = ""
			+ "    .mc { --mc-pre: #86b6ef; --mc-post: #1c5cab; --mc-link: #9ec5f4; --mc-grid: #e6e5e0;"
			+ " --mc-ink: #1f2328; --mc-muted: #57606a; --mc-up: #006300; --mc-down: #b42318; margin-top: 0.75rem; }\n"
			+ "    .mc-summary { margin: 0.25rem 0 0.5rem; }\n"
			+ "    .mc-legend { display: flex; gap: 1.25rem; font-size: 0.85rem; color: var(--mc-muted); margin-bottom: 0.25rem; }\n"
			+ "    .mc-legend svg { vertical-align: -0.2rem; margin-right: 0.3rem; }\n"
			+ "    .mc-row { display: grid; grid-template-columns: minmax(10rem, 2fr) minmax(12rem, 5fr) minmax(10rem, 1.6fr);"
			+ " column-gap: 1.25rem; row-gap: 0.2rem; align-items: center; padding: 0.2rem 0; }\n"
			+ "    .mc-row + .mc-row { border-top: 1px solid var(--mc-grid); }\n"
			+ "    .mc-plot-wrap { padding: 0 " + MEASURE_PLOT_INSET + "px; min-width: 0; }\n"
			+ "    .mc-row svg.mc-plot { width: 100%; overflow: visible; display: block; }\n"
			+ "    .mc-label { font-size: 0.9rem; color: var(--mc-ink); min-width: 0; overflow-wrap: anywhere; }\n"
			+ "    .mc-values { font-size: 0.85rem; color: var(--mc-muted); font-variant-numeric: tabular-nums; }\n"
			+ "    .mc-delta { font-weight: 600; margin-left: 0.4rem; white-space: nowrap; }\n"
			+ "    .mc-delta.up { color: var(--mc-up); }\n"
			+ "    .mc-delta.down { color: var(--mc-down); }\n"
			+ "    .mc-delta.same { color: var(--mc-muted); font-weight: 400; }\n"
			+ "    .mc-group { font-size: 0.8rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em;"
			+ " color: var(--mc-muted); margin: 0.9rem 0 0.2rem; }\n"
			+ "    .mc-row.unchanged .mc-label { color: var(--mc-muted); }\n"
			+ "    .mc-axis { font-size: 0.75rem; fill: var(--mc-muted); }\n"
			+ "    .mc-row.mc-axis-row { border-top: none; padding-bottom: 0; }\n"
			+ "    @media (max-width: 40rem) { .mc-row { grid-template-columns: 1fr; gap: 0.2rem; } .mc-axis-row { display: none; } }\n";
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
				.append(MEASURE_CHART_CSS)
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
	 * Appends the section comparing pre-amendment and post-amendment multi-record measures.
	 *
	 * <p>The comparison is drawn as a dumbbell chart: one row per measure on a shared 0–100% axis,
	 * with the pre-amendment value as a hollow ring and the post-amendment value as a filled dot
	 * joined by a bar, so an improvement reads as a dot to the right of its ring and the bar's
	 * length is the size of the change. Measures that changed come first, largest improvement at
	 * the top; unchanged measures follow, muted. The same numbers are in a table view below.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary supplying measure responses
	 */
	private static void appendMeasureDifferenceVisualization(StringBuilder builder, ExecutionSummary summary) {
		List<StructuredMeasureComparisons.MeasureComparison> comparisons = StructuredMeasureComparisons.summarize(summary);
		if (comparisons.isEmpty()) {
			return;
		}
		List<StructuredMeasureComparisons.MeasureComparison> changed = comparisons.stream()
				.filter(StructuredMeasureComparisons.MeasureComparison::changed)
				.sorted(java.util.Comparator.comparingInt(
						(StructuredMeasureComparisons.MeasureComparison comparison) -> comparison.deltaPercent() == null
								? Integer.MIN_VALUE
								: comparison.deltaPercent())
						.reversed())
				.toList();
		List<StructuredMeasureComparisons.MeasureComparison> unchanged = comparisons.stream()
				.filter(comparison -> !comparison.changed())
				.toList();
		long improved = changed.stream().filter(comparison -> deltaOf(comparison) > 0).count();
		long declined = changed.stream().filter(comparison -> deltaOf(comparison) < 0).count();
		builder.append("<section>\n")
				.append("  <h2>Measure differences between pre-amendment and post-amendment phases</h2>\n")
				.append("  <p class=\"mc-summary\">")
				.append(changed.size())
				.append(" of ")
				.append(comparisons.size())
				.append(" measure(s) changed after amendment: ")
				.append(improved)
				.append(" improved, ")
				.append(declined)
				.append(" declined.</p>\n")
				.append("  <div class=\"mc\">\n")
				.append("    <div class=\"mc-legend\">")
				.append("<span>").append(measureMarkerIcon(false)).append("Pre-amendment</span>")
				.append("<span>").append(measureMarkerIcon(true)).append("Post-amendment</span>")
				.append("</div>\n");
		appendMeasureAxisRow(builder);
		appendMeasureChartGroup(builder, "Changed after amendment", changed, false);
		appendMeasureChartGroup(builder, "Unchanged", unchanged, true);
		builder.append("  </div>\n")
				.append("  <details>\n")
				.append("    <summary>Table view</summary>\n");
		appendMeasureComparisonTable(builder, "Measures with differences", changed);
		appendMeasureComparisonTable(builder, "Measures with no differences", unchanged);
		builder.append("  </details>\n")
				.append("</section>\n");
	}

	/**
	 * @param comparison a measure comparison
	 * @return its percentage-point change, or {@code 0} when unavailable
	 */
	private static int deltaOf(StructuredMeasureComparisons.MeasureComparison comparison) {
		return comparison.deltaPercent() == null ? 0 : comparison.deltaPercent();
	}

	/**
	 * Appends the chart's axis row, labelling the shared 0–100% scale.
	 *
	 * @param builder the report being built; appended to in place
	 */
	private static void appendMeasureAxisRow(StringBuilder builder) {
		builder.append("    <div class=\"mc-row mc-axis-row\" aria-hidden=\"true\"><span></span>")
				.append("<div class=\"mc-plot-wrap\"><svg class=\"mc-plot\" height=\"14\">");
		for (int tick : MEASURE_AXIS_TICKS) {
			String anchor = tick == 0 ? "start" : tick == 100 ? "end" : "middle";
			builder.append("<text class=\"mc-axis\" x=\"").append(tick).append("%\" y=\"11\" text-anchor=\"")
					.append(anchor).append("\">").append(tick).append("%</text>");
		}
		builder.append("</svg></div><span></span></div>\n");
	}

	/**
	 * Appends one group of chart rows under a small heading.
	 *
	 * @param builder the report being built; appended to in place
	 * @param title the group heading
	 * @param comparisons the comparisons in the group, in display order
	 * @param muted whether the rows are drawn de-emphasized
	 */
	private static void appendMeasureChartGroup(
			StringBuilder builder,
			String title,
			List<StructuredMeasureComparisons.MeasureComparison> comparisons,
			boolean muted) {
		if (comparisons.isEmpty()) {
			return;
		}
		builder.append("    <div class=\"mc-group\">")
				.append(escapeHtml(title))
				.append(" (")
				.append(comparisons.size())
				.append(")</div>\n");
		for (StructuredMeasureComparisons.MeasureComparison comparison : comparisons) {
			appendMeasureChartRow(builder, comparison, muted);
		}
	}

	/**
	 * Appends one dumbbell row: the measure label, its pre/post plot, and its values and change.
	 *
	 * @param builder the report being built; appended to in place
	 * @param comparison the measure comparison
	 * @param muted whether the row is drawn de-emphasized
	 */
	private static void appendMeasureChartRow(
			StringBuilder builder,
			StructuredMeasureComparisons.MeasureComparison comparison,
			boolean muted) {
		builder.append("    <div class=\"mc-row").append(muted ? " unchanged" : "").append("\">")
				.append("<span class=\"mc-label\">").append(breakableLabel(comparison.label())).append("</span>")
				.append("<div class=\"mc-plot-wrap\">").append(renderMeasurePlot(comparison)).append("</div>")
				.append("<span class=\"mc-values\">")
				.append(escapeHtml(compactValues(comparison)))
				.append(" <span class=\"mc-delta ").append(deltaCssClass(comparison)).append("\">")
				.append(escapeHtml(deltaLabel(comparison)))
				.append("</span></span></div>\n");
	}

	/**
	 * Escapes a measure label, allowing line breaks after underscores so long test identifiers
	 * (e.g. {@code VALIDATION_COORDINATESTERRESTRIALMARINE_CONSISTENT}) wrap within the label column
	 * rather than running under the plot.
	 *
	 * @param label the raw label
	 * @return the escaped label with break opportunities
	 */
	private static String breakableLabel(String label) {
		return escapeHtml(label).replace("_", "_<wbr>");
	}

	/**
	 * Draws one row's plot: gridlines, the bar joining pre to post, and the two markers.
	 *
	 * @param comparison the measure comparison
	 * @return the plot's SVG, or a short note when the measure has no percentages
	 */
	private static String renderMeasurePlot(StructuredMeasureComparisons.MeasureComparison comparison) {
		Integer pre = clampPercent(comparison.prePercent());
		Integer post = clampPercent(comparison.postPercent());
		if (pre == null && post == null) {
			return "<span class=\"mc-values\">no percentage to plot</span>";
		}
		int middle = MEASURE_ROW_HEIGHT / 2;
		StringBuilder svg = new StringBuilder("<svg class=\"mc-plot\" height=\"").append(MEASURE_ROW_HEIGHT)
				.append("\" role=\"img\" aria-label=\"")
				.append(escapeAttribute(comparison.label() + ": " + comparison.preText() + " before, "
						+ comparison.postText() + " after amendment"))
				.append("\"><title>")
				.append(escapeHtml(comparison.label() + "\npre-amendment: " + comparison.preText()
						+ "\npost-amendment: " + comparison.postText() + "\n" + comparison.changeText()))
				.append("</title>");
		for (int tick : MEASURE_AXIS_TICKS) {
			svg.append("<line x1=\"").append(tick).append("%\" x2=\"").append(tick).append("%\" y1=\"2\" y2=\"")
					.append(MEASURE_ROW_HEIGHT - 2).append("\" stroke=\"var(--mc-grid)\" stroke-width=\"1\"/>");
		}
		if (pre != null && post != null && !pre.equals(post)) {
			svg.append("<line x1=\"").append(pre).append("%\" x2=\"").append(post).append("%\" y1=\"").append(middle)
					.append("\" y2=\"").append(middle)
					.append("\" stroke=\"var(--mc-link)\" stroke-width=\"6\" stroke-linecap=\"round\"/>");
		}
		if (pre != null) {
			svg.append("<circle cx=\"").append(pre).append("%\" cy=\"").append(middle).append("\" r=\"")
					.append(MEASURE_MARKER_RADIUS)
					.append("\" fill=\"#fcfcfb\" stroke=\"var(--mc-pre)\" stroke-width=\"3\"/>");
		}
		if (post != null) {
			svg.append("<circle cx=\"").append(post).append("%\" cy=\"").append(middle).append("\" r=\"")
					.append(pre != null && pre.equals(post) ? MEASURE_MARKER_RADIUS - 3 : MEASURE_MARKER_RADIUS)
					.append("\" fill=\"var(--mc-post)\" stroke=\"#fcfcfb\" stroke-width=\"2\"/>");
		}
		return svg.append("</svg>").toString();
	}

	/**
	 * Draws a legend marker.
	 *
	 * @param post whether to draw the post-amendment (filled) marker rather than the pre-amendment ring
	 * @return the marker's SVG
	 */
	private static String measureMarkerIcon(boolean post) {
		return post
				? "<svg width=\"14\" height=\"14\" aria-hidden=\"true\"><circle cx=\"7\" cy=\"7\" r=\"6\" "
						+ "fill=\"var(--mc-post)\"/></svg>"
				: "<svg width=\"14\" height=\"14\" aria-hidden=\"true\"><circle cx=\"7\" cy=\"7\" r=\"5\" "
						+ "fill=\"#fcfcfb\" stroke=\"var(--mc-pre)\" stroke-width=\"3\"/></svg>";
	}

	/**
	 * Renders a row's values compactly: rounded percentages when both phases have them, otherwise
	 * the phases' text; counts are in the row's tooltip and the table view.
	 *
	 * @param comparison the measure comparison
	 * @return the compact values
	 */
	private static String compactValues(StructuredMeasureComparisons.MeasureComparison comparison) {
		if (comparison.prePercent() != null && comparison.postPercent() != null) {
			return comparison.prePercent() + "% → " + comparison.postPercent() + "%";
		}
		return comparison.preText() + " → " + comparison.postText();
	}

	/**
	 * Labels a measure's change with a direction arrow and signed percentage points.
	 *
	 * @param comparison the measure comparison
	 * @return the change label
	 */
	private static String deltaLabel(StructuredMeasureComparisons.MeasureComparison comparison) {
		Integer delta = comparison.deltaPercent();
		if (delta == null) {
			return comparison.changed() ? "changed" : "no change";
		}
		if (delta == 0) {
			return "no change";
		}
		return delta > 0 ? "▲ +" + delta + " pts" : "▼ −" + Math.abs(delta) + " pts";
	}

	/**
	 * @param comparison the measure comparison
	 * @return {@code up}, {@code down}, or {@code same}
	 */
	private static String deltaCssClass(StructuredMeasureComparisons.MeasureComparison comparison) {
		int delta = deltaOf(comparison);
		return delta > 0 ? "up" : delta < 0 ? "down" : "same";
	}

	/**
	 * @param percent a percentage, possibly {@code null}
	 * @return the percentage clamped to 0–100, or {@code null}
	 */
	private static Integer clampPercent(Integer percent) {
		return percent == null ? null : Math.max(0, Math.min(100, percent));
	}

	/**
	 * Appends one measure-comparison table (the chart's table view).
	 *
	 * @param builder the report being built; appended to in place
	 * @param title the table title
	 * @param comparisons the comparisons to render
	 */
	private static void appendMeasureComparisonTable(
			StringBuilder builder,
			String title,
			List<StructuredMeasureComparisons.MeasureComparison> comparisons) {
		builder.append("  <h3>")
				.append(escapeHtml(title))
				.append("</h3>\n");
		if (comparisons.isEmpty()) {
			builder.append("  <p><em>None.</em></p>\n");
			return;
		}
		builder.append("  <table>\n")
				.append("    <thead><tr><th>Measure</th><th>Pre-amendment</th><th>Post-amendment</th><th>Observed change</th></tr></thead>\n")
				.append("    <tbody>\n");
		for (StructuredMeasureComparisons.MeasureComparison comparison : comparisons) {
			builder.append("      <tr><td>")
					.append(escapeHtml(comparison.label()))
					.append("</td><td>")
					.append(escapeHtml(comparison.preText()))
					.append("</td><td>")
					.append(escapeHtml(comparison.postText()))
					.append("</td><td>")
					.append(escapeHtml(comparison.changeText()))
					.append("</td></tr>\n");
		}
		builder.append("    </tbody>\n")
				.append("  </table>\n");
	}

	/**
	 * Escapes text for a double-quoted HTML attribute value.
	 *
	 * @param raw the raw text
	 * @return the escaped text
	 */
	private static String escapeAttribute(String raw) {
		return escapeHtml(raw).replace("\"", "&quot;");
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
