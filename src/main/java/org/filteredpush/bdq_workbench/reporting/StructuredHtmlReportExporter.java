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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.model.SyntheticDataMarkers;

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
		ReportDigest digest = ReportDigest.from(summary);
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
				.append("    .data-notice { border: 2px solid #9a6700; background: #fff8c5; color: #1f2328; padding: 0.6rem 1rem;"
						+ " border-radius: 6px; margin: 1rem 0; }\n")
				.append("    .muted { color: #57606a; }\n")
				.append("    td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }\n")
				.append("    .data-warning { border: 2px solid #b42318; background: #fef3f2; color: #1f2328; padding: 0.75rem 1rem;"
						+ " border-radius: 6px; margin: 1rem 0; }\n")
				.append(InputViewDiagram.CSS)
				.append("  </style>\n")
				.append("</head>\n")
				.append("<body>\n")
				.append("<h1>BDQ Workbench Structured Report</h1>\n");
		appendSyntheticDataWarning(builder, summary);
		appendExternalPrerequisitesNotice(builder, digest);
		appendRunMetadata(builder, summary, digest);
		appendInputView(builder, summary, InputViewOverview.from(summary), digest);
		appendHighImpactActionItems(builder, digest);
		appendMeasureDifferenceVisualization(builder, summary);
		appendQualitySection(builder, digest);
		appendTestFindings(builder, digest);
		appendEmptyTerms(builder, digest);
		appendAmendments(builder, digest);
		appendRecordsNeedingAttention(builder, digest);
		appendTestsUnableToRun(builder, digest);
		builder.append("</body>\n</html>\n");
		return builder.toString();
	}

	/**
	 * Appends a prominent warning when the input contains records marked as synthetic, modified,
	 * or example data.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary whose input description carries the scan
	 */
	private static void appendSyntheticDataWarning(StringBuilder builder, ExecutionSummary summary) {
		SyntheticDataMarkers markers = summary.dataset().inputDescription().syntheticMarkers();
		if (!markers.found()) {
			return;
		}
		builder.append("<div class=\"data-warning\" role=\"alert\"><strong>⚠ ")
				.append(escapeHtml(markers.summaryLine()))
				.append(".</strong> ")
				.append(escapeHtml(markers.warning()))
				.append("</div>\n");
	}

	/**
	 * Appends run metadata to the report preamble.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary carrying run metadata
	 * @param digest the run's condensed findings
	 */
	private static void appendRunMetadata(
			StringBuilder builder,
			ExecutionSummary summary,
			ReportDigest digest) {
		builder.append("<section>\n")
				.append("  <h2>Run metadata</h2>\n")
				.append("  <ul>\n")
				.append("    <li><strong>Use case:</strong> ")
				.append(escapeHtml(describeUseCase(summary)))
				.append("</li>\n")
				.append("    <li><strong>Input file:</strong> ")
				.append(escapeHtml(summary.metadata().inputFile().isBlank() ? "<unknown>" : summary.metadata().inputFile()))
				.append("</li>\n")
				.append("    <li><strong>Synthetic or modified example data:</strong> ")
				.append(escapeHtml(summary.dataset().inputDescription().syntheticMarkers().summaryLine()))
				.append("</li>\n")
				.append("    <li><strong>Records with quality for this use case:</strong> ")
				.append(escapeHtml(digest.qualityLine()))
				.append("</li>\n")
				.append("    <li><strong>External prerequisites not met:</strong> ")
				.append(escapeHtml(digest.externalPrerequisiteLine()))
				.append("</li>\n")
				.append("    <li><strong>Run started:</strong> ")
				.append(escapeHtml(formatInstant(digest.runStartedAt())))
				.append("</li>\n")
				.append("    <li><strong>Run finished:</strong> ")
				.append(escapeHtml(formatInstant(digest.runFinishedAt())))
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
	 * @param digest the run's condensed findings, for the tests evaluated per related row
	 */
	private static void appendInputView(StringBuilder builder, ExecutionSummary summary, InputViewOverview overview,
			ReportDigest digest) {
		builder.append("<section>\n")
				.append("  <h2>Input data view</h2>\n");
		if (!overview.isKnown()) {
			builder.append("  <p><em>The view used to run the tests was not recorded by ingest.</em></p>\n");
			if (!digest.expandedTests().isEmpty()) {
				builder.append("  <ul>\n");
				appendExpandedTests(builder, digest, "");
				builder.append("  </ul>\n");
			}
			builder.append("</section>\n");
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
				.append(overview.inputRecordCount())
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
			builder.append("    <li><strong>Related-row multiplicity")
					.append(overview.filtered() ? " in the " + description.viewRecordCount() + " selected record(s)" : "")
					.append(":</strong>\n      <ul>\n");
			notes.forEach(note -> builder.append("        <li>").append(escapeHtml(note)).append("</li>\n"));
			builder.append("      </ul>\n    </li>\n");
		}
		appendExpandedTests(builder, digest, description.grainTable());
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
	 * Lists the tests evaluated once per related row, with their evaluation counts.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 * @param grainTable the grain table, left out of the listed row sources
	 */
	private static void appendExpandedTests(StringBuilder builder, ReportDigest digest, String grainTable) {
		List<ReportDigest.ExpandedTest> expanded = digest.expandedTests();
		if (expanded.isEmpty()) {
			return;
		}
		builder.append("    <li><strong>Tests evaluated once per related row:</strong>\n      <ul>\n");
		for (ReportDigest.ExpandedTest test : expanded) {
			List<String> sources = test.relations().stream()
					.filter(relation -> !relation.equalsIgnoreCase(grainTable))
					.toList();
			builder.append("        <li>")
					.append(escapeHtml(test.testLabel()))
					.append(" — ")
					.append(test.evaluations())
					.append(" evaluations over ")
					.append(test.records())
					.append(" record(s)")
					.append(sources.isEmpty() ? "" : ", one per " + escapeHtml(String.join(", ", sources)) + " row")
					.append(test.relations().stream().anyMatch(relation -> relation.equalsIgnoreCase(grainTable))
							? " plus the record's own value"
							: "")
					.append("</li>\n");
		}
		builder.append("      </ul>\n    </li>\n");
	}

	/**
	 * Appends the findings most worth acting on first: issues, validation non-compliance and its
	 * most frequent causes, the amendment proposals that improved the most records, and the
	 * information elements empty in every record.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendHighImpactActionItems(StringBuilder builder, ReportDigest digest) {
		ReportDigest.HighImpact items = digest.highImpact();
		builder.append("<section>\n  <h2>High-impact action items</h2>\n  <ul>\n")
				.append("    <li><strong>Review issue findings:</strong> ")
				.append(items.confirmedIssueRecords()).append(" record(s) with confirmed issues, ")
				.append(items.potentialIssueRecords()).append(" with potential issues</li>\n")
				.append("    <li><strong>Validation non-compliance after amendment:</strong> ")
				.append(items.nonComplianceFindings()).append(" finding(s) across ")
				.append(items.recordsWithNonCompliance()).append(" record(s)</li>\n")
				.append("    <li><strong>Most frequent causes of validation non-compliance:</strong>");
		if (items.topCauses().isEmpty()) {
			builder.append(" none</li>\n");
		} else {
			builder.append("\n      <ol>\n");
			items.topCauses().forEach(cause -> builder.append("        <li>")
					.append(escapeHtml(cause.testLabel())).append(" — ")
					.append(cause.latest().problems()).append(" record(s)</li>\n"));
			builder.append("      </ol>\n    </li>\n");
		}
		builder.append("    <li><strong>Most effective amendment proposals:</strong>");
		if (items.topAmendments().isEmpty()) {
			builder.append(" none</li>\n");
		} else {
			builder.append("\n      <ol>\n");
			items.topAmendments().forEach(group -> builder.append("        <li><code>")
					.append(escapeHtml(group.term())).append("</code>: ")
					.append(escapeHtml(ReportDigest.displayValue(group.originalValue()))).append(" → <strong>")
					.append(escapeHtml(ReportDigest.displayValue(group.proposedValue()))).append("</strong> (")
					.append(escapeHtml(group.testLabel())).append(") — ")
					.append(group.recordCount()).append(" record(s), ")
					.append(group.improvedRecords()).append(" with fewer problems after amendment</li>\n"));
			builder.append("      </ol>\n    </li>\n");
		}
		builder.append("    <li><strong>Information elements empty in every record:</strong> ")
				.append(items.emptyTerms().isEmpty()
						? "none"
						: escapeHtml(limitedList(items.emptyTerms().stream().map(ReportDigest.EmptyTerm::term).toList(),
								ReportDigest.MAX_TESTS_PER_TERM)))
				.append("</li>\n  </ul>\n</section>\n");
	}

	/**
	 * Appends a notice when some results could not be determined for want of an external resource.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendExternalPrerequisitesNotice(StringBuilder builder, ReportDigest digest) {
		if (digest.externalPrerequisiteCount() == 0) {
			return;
		}
		builder.append("<div class=\"data-notice\" role=\"note\"><strong>External prerequisites not met:</strong> ")
				.append(escapeHtml(digest.externalPrerequisiteLine()))
				.append(".</div>\n");
	}

	/**
	 * Appends the count and list of records meeting every multi-record QA measure.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendQualitySection(StringBuilder builder, ReportDigest digest) {
		ReportDigest.QualitySummary quality = digest.qualitySummary();
		builder.append("<section>\n  <h2>Records with quality for this use case</h2>\n");
		if (!quality.hasMeasures()) {
			builder.append("  <p class=\"muted\">The use case defines no multi-record QA measures, so records cannot be "
					+ "assessed as having quality for it.</p>\n</section>\n");
			return;
		}
		builder.append("  <p><strong>")
				.append(escapeHtml(digest.qualityLine()))
				.append(".</strong> A record has quality for the use case when its result for every QA measure's test "
						+ "is COMPLETE.</p>\n");
		if (!quality.recordIds().isEmpty()) {
			builder.append("  <p>")
					.append(escapeHtml(limitedList(quality.recordIds().stream().map(digest::recordLabel).toList(),
							ReportDigest.MAX_QUALITY_RECORDS)))
					.append("</p>\n");
		}
		builder.append("  <details><summary>QA measures (")
				.append(quality.measureLabels().size())
				.append(")</summary><p>")
				.append(escapeHtml(String.join(", ", quality.measureLabels())))
				.append("</p></details>\n</section>\n");
	}

	/**
	 * Appends one row per VALIDATION and ISSUE test, with problem counts before and after amendment.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendTestFindings(StringBuilder builder, ReportDigest digest) {
		List<ReportDigest.TestFindings> findings = digest.testFindings();
		builder.append("<section>\n  <h2>Quality control by test</h2>\n");
		if (findings.isEmpty()) {
			builder.append("  <p class=\"muted\">No validations or issues ran.</p>\n</section>\n");
			return;
		}
		builder.append("  <p class=\"muted\">Problems are NOT_COMPLIANT validations and potential or actual issues, "
						+ "counted per record; tests with the most problems after amendment come first.</p>\n")
				.append("  <table>\n    <thead><tr><th>Test</th><th>Problems (before → after amendment)</th>"
						+ "<th>Not assessable (internal / external prerequisites)</th><th>Example records</th></tr></thead>\n"
						+ "    <tbody>\n");
		for (ReportDigest.TestFindings row : findings) {
			ReportDigest.PhaseCounts latest = row.latest();
			builder.append("      <tr><td>")
					.append(escapeHtml(row.testLabel()))
					.append(row.type() == TestType.ISSUE ? " <span class=\"muted\">(issue)</span>" : "")
					.append("</td><td class=\"num\">")
					.append(escapeHtml(problemTransition(row)))
					.append("</td><td class=\"num\">")
					.append(latest.internalPrerequisites()).append(" / ").append(latest.externalPrerequisites())
					.append("</td><td>")
					.append(escapeHtml(String.join("; ", row.exampleRecords())))
					.append("</td></tr>\n");
		}
		builder.append("    </tbody>\n  </table>\n</section>\n");
	}

	/**
	 * Renders a test's problem counts before and after amendment.
	 *
	 * @param row the test's findings
	 * @return e.g. {@code "12 → 3 of 69"}
	 */
	static String problemTransition(ReportDigest.TestFindings row) {
		ReportDigest.PhaseCounts latest = row.latest();
		String records = row.pre() != null && row.post() != null
				? row.pre().problems() + " → " + row.post().problems() + " of " + latest.records()
				: latest.problems() + " of " + latest.records();
		if (!latest.expanded()) {
			return records;
		}
		return records + " records (" + latest.problemEvaluations() + " of " + latest.evaluations() + " evaluations)";
	}

	/**
	 * Appends the information elements empty in every record.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendEmptyTerms(StringBuilder builder, ReportDigest digest) {
		List<ReportDigest.EmptyTerm> empty = digest.consistentlyEmptyTerms();
		builder.append("<section>\n  <h2>Information elements empty in every record</h2>\n");
		if (empty.isEmpty()) {
			builder.append("  <p class=\"muted\">None: every information element the tests read has a value in at "
					+ "least one record.</p>\n</section>\n");
			return;
		}
		builder.append("  <p>")
				.append(empty.size())
				.append(" term(s) the tests read have no value in any record, so those tests cannot assess them:</p>\n")
				.append("  <table>\n    <thead><tr><th>Term</th><th>In the input</th><th>Tests reading it</th></tr>"
						+ "</thead>\n    <tbody>\n");
		for (ReportDigest.EmptyTerm term : empty) {
			builder.append("      <tr><td><code>")
					.append(escapeHtml(term.term()))
					.append("</code></td><td>")
					.append(term.presentInInput() ? "column present, always empty" : "no such column")
					.append("</td><td>")
					.append(escapeHtml(limitedList(term.tests(), ReportDigest.MAX_TESTS_PER_TERM)))
					.append("</td></tr>\n");
		}
		builder.append("    </tbody>\n  </table>\n</section>\n");
	}

	/**
	 * Appends the proposed amendments, grouped by change and ranked by records affected.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendAmendments(StringBuilder builder, ReportDigest digest) {
		List<ReportDigest.AmendmentGroup> groups = digest.amendmentGroups();
		builder.append("<section>\n  <h2>Proposed amendments</h2>\n");
		if (groups.isEmpty()) {
			builder.append("  <p class=\"muted\">No amendments were proposed.</p>\n</section>\n");
			return;
		}
		builder.append("  <p>")
				.append(groups.size())
				.append(" distinct change(s) proposed; the most widely applicable first.</p>\n")
				.append("  <table>\n    <thead><tr><th>Records</th><th>Change</th><th>Proposed by</th>"
						+ "<th>Example records</th></tr></thead>\n    <tbody>\n");
		for (ReportDigest.AmendmentGroup group
				: groups.subList(0, Math.min(ReportDigest.MAX_AMENDMENT_GROUPS, groups.size()))) {
			builder.append("      <tr><td class=\"num\">")
					.append(group.recordCount())
					.append("</td><td><code>")
					.append(escapeHtml(group.term()))
					.append("</code>: ")
					.append(escapeHtml(ReportDigest.displayValue(group.originalValue())))
					.append(" → <strong>")
					.append(escapeHtml(ReportDigest.displayValue(group.proposedValue())))
					.append("</strong></td><td>")
					.append(escapeHtml(group.testLabel()))
					.append("</td><td>")
					.append(escapeHtml(String.join("; ", group.exampleRecords())))
					.append("</td></tr>\n");
		}
		builder.append("    </tbody>\n  </table>\n");
		if (groups.size() > ReportDigest.MAX_AMENDMENT_GROUPS) {
			builder.append("  <p class=\"muted\">… and ")
					.append(groups.size() - ReportDigest.MAX_AMENDMENT_GROUPS)
					.append(" more distinct change(s); see bdq-report-xls.xlsx for every amendment.</p>\n");
		}
		builder.append("</section>\n");
	}

	/**
	 * Appends a capped table of the records with problems after amendment or proposed amendments.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendRecordsNeedingAttention(StringBuilder builder, ReportDigest digest) {
		List<ReportDigest.AttentionRecord> records = digest.recordsNeedingAttention();
		builder.append("<section>\n  <h2>Records needing attention</h2>\n");
		if (records.isEmpty()) {
			builder.append("  <p class=\"muted\">No record has a problem after amendment or a proposed amendment."
					+ "</p>\n</section>\n");
			return;
		}
		int shown = Math.min(ReportDigest.MAX_ATTENTION_RECORDS, records.size());
		builder.append("  <p>")
				.append(records.size())
				.append(" record(s) have a problem after amendment or a proposed amendment")
				.append(records.size() > shown ? "; the " + shown + " with the most problems are shown" : "")
				.append(".</p>\n")
				.append("  <table>\n    <thead><tr><th>Record</th><th>Problems after amendment</th>"
						+ "<th>Proposed amendments</th></tr></thead>\n    <tbody>\n");
		for (ReportDigest.AttentionRecord record : records.subList(0, shown)) {
			builder.append("      <tr><td>")
					.append(escapeHtml(record.recordLabel()))
					.append("</td><td>")
					.append(escapeHtml(record.problems().isEmpty() ? "—" : String.join("; ", record.problems())))
					.append("</td><td>")
					.append(escapeHtml(record.amendments().isEmpty() ? "—" : String.join("; ", record.amendments())))
					.append("</td></tr>\n");
		}
		builder.append("    </tbody>\n  </table>\n");
		if (records.size() > shown) {
			builder.append("  <p class=\"muted\">Every record's results are in bdq-report-xls.xlsx and "
					+ "bdq-report-responses.txt.</p>\n");
		}
		builder.append("</section>\n");
	}

	/**
	 * Appends the tests that could not run at all.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendTestsUnableToRun(StringBuilder builder, ReportDigest digest) {
		Map<String, String> unable = digest.testsUnableToRun();
		if (unable.isEmpty()) {
			return;
		}
		builder.append("<section>\n  <h2>Tests that could not run</h2>\n  <ul>\n");
		unable.forEach((test, reason) -> builder.append("    <li>")
				.append(escapeHtml(test))
				.append(reason.isBlank() ? "" : " <span class=\"muted\">— " + escapeHtml(reason) + "</span>")
				.append("</li>\n"));
		builder.append("  </ul>\n</section>\n");
	}

	/**
	 * Joins values, listing at most {@code max} and counting the rest.
	 *
	 * @param values the values
	 * @param max the most to list
	 * @return e.g. {@code "a, b, c (+4 more)"}
	 */
	static String limitedList(List<String> values, int max) {
		String listed = String.join(", ", values.subList(0, Math.min(max, values.size())));
		return values.size() > max ? listed + " (+" + (values.size() - max) + " more)" : listed;
	}

	/**
	 * Appends the section comparing pre-amendment and post-amendment multi-record measures, with
	 * COUNT and QA measures presented separately since their values mean different things.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary supplying measure responses
	 */
	private static void appendMeasureDifferenceVisualization(StringBuilder builder, ExecutionSummary summary) {
		List<StructuredMeasureComparisons.MeasureComparison> comparisons = StructuredMeasureComparisons.summarize(summary);
		builder.append("<section>\n")
				.append("  <h2>Measure differences between pre-amendment and post-amendment phases</h2>\n");
		if (comparisons.isEmpty()) {
			builder.append("  <p class=\"muted\">No multi-record measures were produced in this run, so there is "
							+ "nothing to compare.</p>\n")
					.append("</section>\n");
			return;
		}
		appendCountMeasureChart(builder,
				StructuredMeasureComparisons.ofKind(comparisons, BuiltInMeasureSpec.MeasureKind.COUNT));
		appendQaMeasureTable(builder,
				StructuredMeasureComparisons.ofKind(comparisons, BuiltInMeasureSpec.MeasureKind.QA));
		builder.append("</section>\n");
	}

	/**
	 * Appends the COUNT measures' comparison, drawn as a dumbbell chart: one row per measure on a
	 * shared 0–100% axis, with the pre-amendment value as a hollow ring and the post-amendment value
	 * as a filled dot joined by a bar, so an improvement reads as a dot to the right of its ring and
	 * the bar's length is the size of the change. Measures that changed come first, largest
	 * improvement at the top; unchanged measures follow, muted. The same numbers are in a table view
	 * below.
	 *
	 * @param builder the report being built; appended to in place
	 * @param comparisons the COUNT measure comparisons
	 */
	private static void appendCountMeasureChart(
			StringBuilder builder,
			List<StructuredMeasureComparisons.MeasureComparison> comparisons) {
		builder.append("  <h3>COUNT measures</h3>\n");
		if (comparisons.isEmpty()) {
			builder.append("  <p class=\"muted\">No multi-record COUNT measures were produced in this run.</p>\n");
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
		builder.append("  <p class=\"mc-summary\">")
				.append(changed.size())
				.append(" of ")
				.append(comparisons.size())
				.append(" COUNT measure(s) changed after amendment: ")
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
		builder.append("  </details>\n");
	}

	/**
	 * Appends the QA measures' comparison as a table of their results, COMPLETE or NOT_COMPLETE for
	 * the dataset as a whole, with each phase's pass rate as a muted note beneath its result. QA
	 * measures are not plotted: their value is a result, not a percentage. Measures whose result
	 * changed come first.
	 *
	 * @param builder the report being built; appended to in place
	 * @param comparisons the QA measure comparisons
	 */
	private static void appendQaMeasureTable(
			StringBuilder builder,
			List<StructuredMeasureComparisons.MeasureComparison> comparisons) {
		builder.append("  <h3>QA measures</h3>\n");
		if (comparisons.isEmpty()) {
			builder.append("  <p class=\"muted\">No multi-record QA measures were produced in this run.</p>\n");
			return;
		}
		List<StructuredMeasureComparisons.MeasureComparison> ordered = new ArrayList<>(comparisons);
		ordered.sort(java.util.Comparator.comparing(
				(StructuredMeasureComparisons.MeasureComparison comparison) -> !comparison.changed()));
		long changed = comparisons.stream().filter(StructuredMeasureComparisons.MeasureComparison::changed).count();
		long nowComplete = comparisons.stream()
				.filter(comparison -> qaTransition(comparison) > 0)
				.count();
		long nowNotComplete = comparisons.stream()
				.filter(comparison -> qaTransition(comparison) < 0)
				.count();
		builder.append("  <p class=\"mc-summary\">")
				.append(changed)
				.append(" of ")
				.append(comparisons.size())
				.append(" QA measure(s) changed result after amendment: ")
				.append(nowComplete)
				.append(" became COMPLETE, ")
				.append(nowNotComplete)
				.append(" became NOT_COMPLETE. A QA measure is COMPLETE only when every record meets its "
						+ "criteria.</p>\n")
				.append("  <table>\n")
				.append("    <thead><tr><th>Measure</th><th>Pre-amendment</th><th>Post-amendment</th><th>Change</th></tr></thead>\n")
				.append("    <tbody>\n");
		for (StructuredMeasureComparisons.MeasureComparison comparison : ordered) {
			builder.append("      <tr><td>")
					.append(escapeHtml(comparison.label()))
					.append("</td><td>")
					.append(qaResultCell(comparison.preText(), comparison.prePassRate()))
					.append("</td><td>")
					.append(qaResultCell(comparison.postText(), comparison.postPassRate()))
					.append("</td><td><span class=\"mc-delta ")
					.append(qaTransition(comparison) > 0 ? "up" : qaTransition(comparison) < 0 ? "down" : "same")
					.append("\">")
					.append(escapeHtml(qaChangeLabel(comparison)))
					.append("</span></td></tr>\n");
		}
		builder.append("    </tbody>\n")
				.append("  </table>\n");
	}

	/**
	 * Renders one QA phase cell: the result, with its pass rate as a muted note when known.
	 *
	 * @param result the phase's result text
	 * @param passRate the phase's pass-rate note, or {@code null}
	 * @return the cell's HTML
	 */
	private static String qaResultCell(String result, String passRate) {
		String cell = "<strong>" + escapeHtml(result) + "</strong>";
		return passRate == null ? cell : cell + "<br><span class=\"muted\">" + escapeHtml(passRate) + "</span>";
	}

	/**
	 * Classifies a QA measure's change of result.
	 *
	 * @param comparison a QA measure comparison
	 * @return {@code 1} if it became COMPLETE, {@code -1} if it went from COMPLETE to NOT_COMPLETE,
	 *     otherwise {@code 0}
	 */
	private static int qaTransition(StructuredMeasureComparisons.MeasureComparison comparison) {
		if (!comparison.changed()) {
			return 0;
		}
		if (StructuredMeasureComparisons.COMPLETE.equals(comparison.postText())) {
			return 1;
		}
		boolean lostCompleteness = StructuredMeasureComparisons.COMPLETE.equals(comparison.preText())
				&& StructuredMeasureComparisons.NOT_COMPLETE.equals(comparison.postText());
		return lostCompleteness ? -1 : 0;
	}

	/**
	 * Labels a QA measure's change of result.
	 *
	 * @param comparison a QA measure comparison
	 * @return the change label
	 */
	private static String qaChangeLabel(StructuredMeasureComparisons.MeasureComparison comparison) {
		int transition = qaTransition(comparison);
		if (transition > 0) {
			return "▲ now COMPLETE";
		}
		if (transition < 0) {
			return "▼ now NOT_COMPLETE";
		}
		return comparison.changed() ? comparison.changeText() : "no change";
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
		builder.append("  <h4>")
				.append(escapeHtml(title))
				.append("</h4>\n");
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
}
