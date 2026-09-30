/** StructuredMarkdownReportExporter.java
 *
 * Exports a human-readable Markdown report for flat and structured per-record BDQ responses.
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
 * Exports a human-readable Markdown report for flat and structured results.
 *
 * <p>The rendered report begins with run metadata, an overview of the input data view (input
 * tables and their record counts, whether a flattened view or a view with multiplicity was used,
 * and any tables ignored for lacking test bindings), a ranked "high-impact action items" summary,
 * and a textual list of any multi-record measures whose pre/post values changed, then keeps one
 * top-level section per core record. For flat runs, each test group renders as a single-level
 * summary. For structured runs, each test group renders a core-record-level summary followed by
 * nested detail assertions, including source-row selectors derived from each contributing
 * {@link SubjectRef}. The exporter is additive: it complements the existing summary, tab-delimited,
 * RDF, and XLSX outputs rather than replacing them.
 */
public class StructuredMarkdownReportExporter implements ReportExporter {

	/** The most bound terms named per table in the input-view overview. */
	private static final int MAX_LISTED_TERMS = 6;

	/**
	 * @return {@code "structured"}, the format identifier for this exporter
	 */
	@Override
	public String format() {
		return "structured";
	}

	/**
	 * @return {@code "md"}, since this exporter writes Markdown
	 */
	@Override
	public String fileExtension() {
		return "md";
	}

	/**
	 * Writes a structured Markdown report to the given output stream as UTF-8 text.
	 *
	 * @param summary the execution summary whose responses should be rendered
	 * @param outputStream the stream to write the Markdown report to; not closed by this method
	 * @throws IOException if writing to {@code outputStream} fails
	 */
	@Override
	public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
		outputStream.write(renderMarkdown(summary).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Renders a structured Markdown report for the supplied execution summary.
	 *
	 * @param summary the execution summary whose responses should be rendered
	 * @return the rendered Markdown report
	 */
	public static String renderMarkdown(ExecutionSummary summary) {
		ReportDigest digest = ReportDigest.from(summary);
		StringBuilder builder = new StringBuilder("# BDQ Workbench Structured Report\n\n");
		SyntheticDataMarkers markers = summary.dataset().inputDescription().syntheticMarkers();
		if (markers.found()) {
			builder.append("> **⚠ ")
					.append(escape(markers.summaryLine()))
					.append(".** ")
					.append(escape(markers.warning()))
					.append("\n\n");
		}
		if (digest.externalPrerequisiteCount() > 0) {
			builder.append("> **External prerequisites not met:** ")
					.append(escape(digest.externalPrerequisiteLine()))
					.append(".\n\n");
		}
		appendRunMetadata(builder, summary, digest);
		appendInputView(builder, summary, InputViewOverview.from(summary), digest);
		appendHighImpactActionItems(builder, digest);
		appendMeasureDifferences(builder, summary);
		appendQualitySection(builder, digest);
		appendTestFindings(builder, digest);
		appendEmptyTerms(builder, digest);
		appendAmendments(builder, digest);
		appendRecordsNeedingAttention(builder, digest);
		appendTestsUnableToRun(builder, digest);
		return builder.toString();
	}

	/**
	 * Appends run metadata to the report preamble, in the order a reader follows the run: what was
	 * run on what input, when, which terms and records were selected (filters, then the resulting
	 * record count), and last the outcome, the records with quality for the use case.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary carrying run metadata
	 * @param digest the run's condensed findings
	 */
	private static void appendRunMetadata(
			StringBuilder builder,
			ExecutionSummary summary,
			ReportDigest digest) {
		builder.append("## Run metadata\n\n")
				.append("- Use case: ")
				.append(escape(describeUseCase(summary)))
				.append('\n')
				.append("- Input file: ")
				.append(escape(summary.metadata().inputFile().isBlank() ? "<unknown>" : summary.metadata().inputFile()))
				.append('\n')
				.append("- Synthetic or modified example data: ")
				.append(escape(summary.dataset().inputDescription().syntheticMarkers().summaryLine()))
				.append('\n')
				.append("- External prerequisites not met: ")
				.append(escape(digest.externalPrerequisiteLine()))
				.append('\n')
				.append("- Run started: ")
				.append(escape(formatInstant(digest.runStartedAt())))
				.append('\n')
				.append("- Run finished: ")
				.append(escape(formatInstant(digest.runFinishedAt())))
				.append('\n')
				.append("- Darwin Core terms selected for execution: ")
				.append(summary.metadata().filteredDarwinCoreTermCount())
				.append(" of ")
				.append(summary.metadata().inputDarwinCoreTermCount())
				.append('\n');
		builder.append("- Record filters: ");
		if (summary.metadata().recordFilters().isEmpty()) {
			builder.append("none\n");
		} else {
			builder.append('\n');
			summary.metadata().recordFilters().forEach((field, values) -> builder.append("  - ")
					.append(escape(field))
					.append(" = ")
					.append(escape(String.join(" | ", values)))
					.append('\n'));
		}
		builder.append("- Records selected for execution: ")
				.append(summary.metadata().filteredSingleRecordCount())
				.append(" of ")
				.append(summary.metadata().inputSingleRecordCount())
				.append('\n')
				.append("- Records with quality for this use case: ")
				.append(escape(digest.qualityLine()))
				.append("\n\n");
	}

	/**
	 * Appends an overview of the input tables and the view the tests ran over.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary carrying record-selection metadata
	 * @param overview the input-view overview to render
	 * @param digest the run's condensed findings, for the tests evaluated per related row
	 */
	private static void appendInputView(StringBuilder builder, ExecutionSummary summary, InputViewOverview overview,
			ReportDigest digest) {
		builder.append("## Input data view\n\n");
		if (!overview.isKnown()) {
			builder.append("- View: not recorded by ingest\n");
			appendExpandedTests(builder, digest, "");
			builder.append('\n');
			return;
		}
		DatasetInputDescription description = overview.description();
		builder.append("- View: **")
				.append(escape(overview.modeLabel()))
				.append("** — ")
				.append(escape(overview.modeExplanation()))
				.append('\n');
		if (!description.viewSource().isBlank()) {
			builder.append("- View source: ").append(escape(description.viewSource())).append('\n');
		}
		builder.append("- Grain table: `")
				.append(escape(description.grainTable()))
				.append("` → ")
				.append(overview.inputRecordCount())
				.append(" execution record(s), ")
				.append(summary.metadata().filteredSingleRecordCount())
				.append(" selected after record filtering\n")
				.append("- Input tables: ")
				.append(overview.tables().size())
				.append(" (")
				.append(overview.includedTables().size())
				.append(" used by the view, ")
				.append(overview.ignoredTables().size())
				.append(" ignored for lacking test bindings, ")
				.append(overview.identifyingTables().isEmpty() ? ""
						: overview.identifyingTables().size() + " used only to identify records, ")
				.append(overview.notIncludedTables().size())
				.append(" not included)\n");
		List<String> notes = overview.multiplicityNotes();
		if (!notes.isEmpty()) {
			builder.append("- Related-row multiplicity")
					.append(overview.filtered() ? " in the " + description.viewRecordCount() + " selected record(s)" : "")
					.append(":\n");
			notes.forEach(note -> builder.append("  - ").append(escape(note)).append('\n'));
		}
		appendExpandedTests(builder, digest, description.grainTable());
		builder.append('\n')
				.append("| Table | Row type | Records | Columns | Role in view | Relationship to grain | Tests reading it | Bound terms supplied |\n")
				.append("|---|---|---:|---:|---|---|---:|---|\n");
		for (InputViewOverview.TableOverview table : overview.tables()) {
			builder.append("| `").append(escapeCell(table.name())).append("` | ")
					.append(escapeCell(table.rowType().isBlank() ? "—" : table.rowType())).append(" | ")
					.append(table.recordCountLabel()).append(" | ")
					.append(table.columnCount()).append(" | ")
					.append(escapeCell(table.role().label())).append(" | ")
					.append(escapeCell(table.relationToGrain())).append(" | ")
					.append(table.testCount()).append(" | ")
					.append(escapeCell(table.suppliedTermsSummary(MAX_LISTED_TERMS))).append(" |\n");
		}
		builder.append('\n');
		if (!overview.identifyingTables().isEmpty()) {
			builder.append("Used only to identify records (no test reads them; the reports name records by these "
					+ "terms): ")
					.append(overview.identifyingTables().stream()
							.map(table -> "`" + escape(table.name()) + "` (" + escape(String.join(", ",
									overview.description().recordIdentification().termsFrom(table.name()))) + ")")
							.collect(Collectors.joining(", ")))
					.append("\n\n");
		}
		if (!overview.ignoredTables().isEmpty()) {
			builder.append("Ignored in view construction (no test binding reads any of their terms): ")
					.append(overview.ignoredTables().stream()
							.map(table -> "`" + escape(table.name()) + "`")
							.collect(Collectors.joining(", ")))
					.append("\n\n");
		}
	}

	/**
	 * Escapes text for use inside a Markdown table cell.
	 *
	 * @param raw the raw text to escape
	 * @return the escaped text, with pipes escaped so they do not split the cell
	 */
	private static String escapeCell(String raw) {
		return escape(raw).replace("|", "\\|");
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
		builder.append("- Tests evaluated once per related row:\n");
		for (ReportDigest.ExpandedTest test : expanded) {
			List<String> sources = test.relations().stream()
					.filter(relation -> !relation.equalsIgnoreCase(grainTable))
					.toList();
			builder.append("  - ")
					.append(escape(test.testLabel()))
					.append(" — ")
					.append(test.evaluations())
					.append(" evaluations over ")
					.append(test.records())
					.append(" record(s)")
					.append(sources.isEmpty() ? "" : ", one per " + escape(String.join(", ", sources)) + " row")
					.append(test.relations().stream().anyMatch(relation -> relation.equalsIgnoreCase(grainTable))
							? " plus the record's own value"
							: "")
					.append("\n");
		}
		builder.append("");
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
		builder.append("## High-impact action items\n\n")
				.append("- Review issue findings: ").append(items.confirmedIssueRecords())
				.append(" record(s) with confirmed issues, ").append(items.potentialIssueRecords())
				.append(" with potential issues\n")
				.append("- Validation non-compliance after amendment: ").append(items.nonComplianceFindings())
				.append(" finding(s) across ").append(items.recordsWithNonCompliance()).append(" record(s)\n")
				.append("- Most frequent causes of validation non-compliance:");
		if (items.topCauses().isEmpty()) {
			builder.append(" none\n");
		} else {
			builder.append('\n');
			items.topCauses().forEach(cause -> builder.append("  1. ").append(escape(cause.testLabel())).append(" — ")
					.append(cause.latest().problems()).append(" record(s)\n"));
		}
		builder.append("- Most effective amendment proposals:");
		if (items.topAmendments().isEmpty()) {
			builder.append(" none\n");
		} else {
			builder.append('\n');
			items.topAmendments().forEach(group -> builder.append("  1. `").append(escape(group.term())).append("`: ")
					.append(escape(ReportDigest.displayValue(group.originalValue()))).append(" → **")
					.append(escape(ReportDigest.displayValue(group.proposedValue()))).append("** (")
					.append(escape(group.testLabel())).append(") — ").append(group.recordCount()).append(" record(s), ")
					.append(group.improvedRecords()).append(" with fewer problems after amendment\n"));
		}
		builder.append("- Information elements empty in every record: ")
				.append(items.emptyTerms().isEmpty()
						? "none"
						: escape(StructuredHtmlReportExporter.limitedList(
								items.emptyTerms().stream().map(ReportDigest.EmptyTerm::term).toList(),
								ReportDigest.MAX_TESTS_PER_TERM)))
				.append("\n\n");
	}

	/**
	 * Appends the count and list of records meeting every multi-record QA measure.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendQualitySection(StringBuilder builder, ReportDigest digest) {
		ReportDigest.QualitySummary quality = digest.qualitySummary();
		builder.append("## Records with quality for this use case\n\n");
		if (!quality.hasMeasures()) {
			builder.append("The use case defines no multi-record QA measures, so records cannot be assessed as having "
					+ "quality for it.\n\n");
			return;
		}
		builder.append("**").append(escape(digest.qualityLine())).append(".** A record has quality for the use case "
				+ "when its result for every QA measure's test is COMPLETE.\n\n");
		if (!quality.recordIds().isEmpty()) {
			builder.append(escape(StructuredHtmlReportExporter.limitedList(
					quality.recordIds().stream().map(digest::recordLabel).toList(), ReportDigest.MAX_QUALITY_RECORDS)))
					.append("\n\n");
		}
		builder.append("QA measures: ").append(escape(String.join(", ", quality.measureLabels()))).append("\n\n");
	}

	/**
	 * Appends one row per VALIDATION and ISSUE test, with problem counts before and after amendment.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendTestFindings(StringBuilder builder, ReportDigest digest) {
		List<ReportDigest.TestFindings> findings = digest.testFindings();
		builder.append("## Quality control by test\n\n");
		if (findings.isEmpty()) {
			builder.append("No validations or issues ran.\n\n");
			return;
		}
		builder.append("Problems are NOT_COMPLIANT validations and potential or actual issues, counted per record; "
						+ "tests with the most problems after amendment come first.\n\n")
				.append("| Test | Problems (before → after) | Not assessable (internal / external) | Example records |\n")
				.append("|---|---:|---:|---|\n");
		for (ReportDigest.TestFindings row : findings) {
			ReportDigest.PhaseCounts latest = row.latest();
			builder.append("| ").append(escapeCell(row.testLabel())).append(row.type() == TestType.ISSUE ? " (issue)" : "")
					.append(" | ").append(escapeCell(StructuredHtmlReportExporter.problemTransition(row)))
					.append(" | ").append(latest.internalPrerequisites()).append(" / ").append(latest.externalPrerequisites())
					.append(" | ").append(escapeCell(String.join("; ", row.exampleRecords()))).append(" |\n");
		}
		builder.append('\n');
	}

	/**
	 * Appends the information elements empty in every record.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendEmptyTerms(StringBuilder builder, ReportDigest digest) {
		List<ReportDigest.EmptyTerm> empty = digest.consistentlyEmptyTerms();
		builder.append("## Information elements empty in every record\n\n");
		if (empty.isEmpty()) {
			builder.append("None: every information element the tests read has a value in at least one record.\n\n");
			return;
		}
		builder.append(empty.size()).append(" term(s) the tests read have no value in any record, so those tests cannot "
				+ "assess them:\n\n| Term | In the input | Tests reading it |\n|---|---|---|\n");
		for (ReportDigest.EmptyTerm term : empty) {
			builder.append("| `").append(escapeCell(term.term())).append("` | ")
					.append(term.presentInInput() ? "column present, always empty" : "no such column").append(" | ")
					.append(escapeCell(StructuredHtmlReportExporter.limitedList(term.tests(), ReportDigest.MAX_TESTS_PER_TERM)))
					.append(" |\n");
		}
		builder.append('\n');
	}

	/**
	 * Appends the proposed amendments, grouped by change and ranked by records affected.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendAmendments(StringBuilder builder, ReportDigest digest) {
		List<ReportDigest.AmendmentGroup> groups = digest.amendmentGroups();
		builder.append("## Proposed amendments\n\n");
		if (groups.isEmpty()) {
			builder.append("No amendments were proposed.\n\n");
			return;
		}
		builder.append(groups.size()).append(" distinct change(s) proposed; the most widely applicable first.\n\n")
				.append("| Records | Change | Proposed by | Example records |\n|---:|---|---|---|\n");
		for (ReportDigest.AmendmentGroup group
				: groups.subList(0, Math.min(ReportDigest.MAX_AMENDMENT_GROUPS, groups.size()))) {
			builder.append("| ").append(group.recordCount())
					.append(" | `").append(escapeCell(group.term())).append("`: ")
					.append(escapeCell(ReportDigest.displayValue(group.originalValue()))).append(" → **")
					.append(escapeCell(ReportDigest.displayValue(group.proposedValue()))).append("** | ")
					.append(escapeCell(group.testLabel())).append(" | ")
					.append(escapeCell(String.join("; ", group.exampleRecords()))).append(" |\n");
		}
		builder.append('\n');
		if (groups.size() > ReportDigest.MAX_AMENDMENT_GROUPS) {
			builder.append("… and ").append(groups.size() - ReportDigest.MAX_AMENDMENT_GROUPS)
					.append(" more distinct change(s); see bdq-report-xls.xlsx for every amendment.\n\n");
		}
	}

	/**
	 * Appends a capped table of the records with problems after amendment or proposed amendments.
	 *
	 * @param builder the report being built; appended to in place
	 * @param digest the run's condensed findings
	 */
	private static void appendRecordsNeedingAttention(StringBuilder builder, ReportDigest digest) {
		List<ReportDigest.AttentionRecord> records = digest.recordsNeedingAttention();
		builder.append("## Records needing attention\n\n");
		if (records.isEmpty()) {
			builder.append("No record has a problem after amendment or a proposed amendment.\n\n");
			return;
		}
		int shown = Math.min(ReportDigest.MAX_ATTENTION_RECORDS, records.size());
		builder.append(records.size()).append(" record(s) have a problem after amendment or a proposed amendment")
				.append(records.size() > shown ? "; the " + shown + " with the most problems are shown" : "")
				.append(".\n\n| Record | Problems after amendment | Proposed amendments |\n|---|---|---|\n");
		for (ReportDigest.AttentionRecord record : records.subList(0, shown)) {
			builder.append("| ").append(escapeCell(record.recordLabel())).append(" | ")
					.append(escapeCell(record.problems().isEmpty() ? "—" : String.join("; ", record.problems())))
					.append(" | ")
					.append(escapeCell(record.amendments().isEmpty() ? "—" : String.join("; ", record.amendments())))
					.append(" |\n");
			appendAttentionRows(builder, record);
		}
		builder.append('\n');
		if (records.size() > shown) {
			builder.append("Every record's results are in bdq-report-xls.xlsx and bdq-report-responses.txt.\n\n");
		}
	}

	/**
	 * Appends a record's related rows needing attention as rows beneath it, each naming the row's
	 * file and line and the values its failing tests read.
	 *
	 * @param builder the report being built; appended to in place
	 * @param record the record needing attention
	 */
	private static void appendAttentionRows(StringBuilder builder, ReportDigest.AttentionRecord record) {
		for (ReportDigest.RowAttention row : record.rows()) {
			builder.append("| ↳ ").append(escapeCell(row.rowLabel()));
			if (!row.values().isEmpty()) {
				builder.append(" (").append(escapeCell(String.join("; ", row.values()))).append(')');
			}
			builder.append(" | ")
					.append(escapeCell(row.problems().isEmpty() ? "—" : String.join("; ", row.problems())))
					.append(" | ")
					.append(escapeCell(row.amendments().isEmpty() ? "—" : String.join("; ", row.amendments())))
					.append(" |\n");
		}
		if (record.moreRows() > 0) {
			builder.append("| ↳ ").append(record.moreRows())
					.append(" more related row(s) of this record need attention | | |\n");
		}
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
		builder.append("## Tests that could not run\n\n");
		unable.forEach((test, reason) -> builder.append("- ").append(escape(test))
				.append(reason.isBlank() ? "" : " — " + escape(reason)).append('\n'));
		builder.append('\n');
	}

	/**
	 * Appends a textual summary of the pre- and post-amendment multi-record measures, with COUNT and
	 * QA measures presented separately since their values mean different things.
	 *
	 * @param builder the report being built; appended to in place
	 * @param summary the execution summary supplying multi-record measure responses
	 */
	private static void appendMeasureDifferences(StringBuilder builder, ExecutionSummary summary) {
		List<StructuredMeasureComparisons.MeasureComparison> comparisons = StructuredMeasureComparisons.summarize(summary);
		builder.append("## Measure differences between pre-amendment and post-amendment phases\n\n");
		if (comparisons.isEmpty()) {
			builder.append("No multi-record measures were produced in this run, so there is nothing to compare.\n\n");
			return;
		}
		appendCountMeasureDifferences(builder,
				StructuredMeasureComparisons.ofKind(comparisons, BuiltInMeasureSpec.MeasureKind.COUNT));
		appendQaMeasureTable(builder,
				StructuredMeasureComparisons.ofKind(comparisons, BuiltInMeasureSpec.MeasureKind.QA));
	}

	/**
	 * Appends the COUNT measures, changed ones first by largest improvement.
	 *
	 * @param builder the report being built; appended to in place
	 * @param comparisons the COUNT measure comparisons
	 */
	private static void appendCountMeasureDifferences(
			StringBuilder builder,
			List<StructuredMeasureComparisons.MeasureComparison> comparisons) {
		builder.append("### COUNT measures\n\n");
		if (comparisons.isEmpty()) {
			builder.append("No multi-record COUNT measures were produced in this run.\n\n");
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
		builder.append(changed.size()).append(" of ").append(comparisons.size())
				.append(" COUNT measure(s) changed after amendment.\n\n");
		if (!changed.isEmpty()) {
			builder.append("#### Measures with pre/post differences\n\n");
			for (StructuredMeasureComparisons.MeasureComparison comparison : changed) {
				builder.append("- ").append(escape(comparison.label())).append(": ")
						.append(escape(comparison.preText())).append(" -> ").append(escape(comparison.postText()))
						.append(" (").append(escape(comparison.changeText())).append(")\n");
			}
			builder.append('\n');
		}
		if (!unchanged.isEmpty()) {
			builder.append("#### Measures with no differences\n\n");
			for (StructuredMeasureComparisons.MeasureComparison comparison : unchanged) {
				builder.append("- ").append(escape(comparison.label())).append(": ").append(escape(comparison.postText()))
						.append('\n');
			}
			builder.append('\n');
		}
	}

	/**
	 * Appends the QA measures as a table of their results, COMPLETE or NOT_COMPLETE for the dataset
	 * as a whole, each followed by its pass rate when known. Measures whose result changed come first.
	 *
	 * @param builder the report being built; appended to in place
	 * @param comparisons the QA measure comparisons
	 */
	private static void appendQaMeasureTable(
			StringBuilder builder,
			List<StructuredMeasureComparisons.MeasureComparison> comparisons) {
		builder.append("### QA measures\n\n");
		if (comparisons.isEmpty()) {
			builder.append("No multi-record QA measures were produced in this run.\n\n");
			return;
		}
		List<StructuredMeasureComparisons.MeasureComparison> ordered = new ArrayList<>(comparisons);
		ordered.sort(java.util.Comparator.comparing(
				(StructuredMeasureComparisons.MeasureComparison comparison) -> !comparison.changed()));
		long changed = comparisons.stream().filter(StructuredMeasureComparisons.MeasureComparison::changed).count();
		builder.append(changed).append(" of ").append(comparisons.size())
				.append(" QA measure(s) changed result after amendment. A QA measure is COMPLETE only when every "
						+ "record meets its criteria.\n\n")
				.append("| Measure | Pre-amendment | Post-amendment | Change |\n")
				.append("| --- | --- | --- | --- |\n");
		for (StructuredMeasureComparisons.MeasureComparison comparison : ordered) {
			builder.append("| ").append(escapeCell(comparison.label()))
					.append(" | ").append(qaResultCell(comparison.preText(), comparison.prePassRate()))
					.append(" | ").append(qaResultCell(comparison.postText(), comparison.postPassRate()))
					.append(" | ").append(escapeCell(comparison.changed() ? comparison.changeText() : "no change"))
					.append(" |\n");
		}
		builder.append('\n');
	}

	/**
	 * Renders one QA phase cell: the result, followed by its pass rate when known.
	 *
	 * @param result the phase's result text
	 * @param passRate the phase's pass-rate note, or {@code null}
	 * @return the escaped cell text
	 */
	private static String qaResultCell(String result, String passRate) {
		return passRate == null
				? "**" + escapeCell(result) + "**"
				: "**" + escapeCell(result) + "** — " + escapeCell(passRate);
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
	 * Escapes Markdown-significant characters used in the report's inline content.
	 *
	 * @param raw the raw text to escape
	 * @return the escaped text
	 */
	private static String escape(String raw) {
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
