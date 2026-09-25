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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SubjectRef;

/**
 * Exports a human-readable Markdown report for flat and structured results.
 *
 * <p>The rendered report keeps one top-level section per core record. For flat runs, each test
 * group renders as a single-level summary. For structured runs, each test group renders a
 * core-record-level summary followed by nested detail assertions, including source-row selectors
 * derived from each contributing {@link SubjectRef}. The exporter is additive: it complements the
 * existing summary, tab-delimited, RDF, and XLSX outputs rather than replacing them.
 */
public class StructuredMarkdownReportExporter implements ReportExporter {

	private static final String MULTIRECORD_SENTINEL = "MULTIRECORD";
	private static final String UNRESOLVED_SENTINEL = "*";
	private static final Pattern SYNTHETIC_ROW_REF_PATTERN = Pattern.compile("row-(\\d+)");

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

		StringBuilder builder = new StringBuilder("# BDQ Workbench Structured Report\n\n");
		builder.append("Use case: ")
				.append(describeUseCase(summary))
				.append("\n\n");
		for (Map.Entry<String, List<Response>> entry : responsesByRecord.entrySet()) {
			appendRecordSection(builder, entry.getKey(), entry.getValue(), recordsById.get(entry.getKey()));
		}
		appendAggregateSection(builder, aggregateResponses);
		return builder.toString();
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
				.filter(StructuredMarkdownReportExporter::isPerRecordResponse)
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
		builder.append("## Record `").append(escape(recordId)).append("`\n\n");
		if (record != null && !record.terms().isEmpty()) {
			builder.append("Core terms: ");
			builder.append(record.terms().entrySet().stream()
					.sorted(Map.Entry.comparingByKey())
					.map(entry -> "`" + escape(entry.getKey()) + "=" + escape(entry.getValue()) + "`")
					.collect(Collectors.joining(", ")));
			builder.append("\n\n");
		}
		if (responses.isEmpty()) {
			builder.append("_No responses emitted for this record._\n\n");
			return;
		}

		for (Map.Entry<TestGroupKey, List<Response>> entry : groupByTest(responses).entrySet()) {
			appendTestGroup(builder, entry.getKey(), entry.getValue());
		}
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
		builder.append("### ").append(escape(key.phase())).append(" · ")
				.append(escape(key.testType())).append(" · `")
				.append(escape(key.testId()))
				.append("`\n\n");

		Response rollup = responses.stream().filter(Response::derived).findFirst().orElse(null);
		List<Response> detailResponses = responses.stream()
				.filter(response -> !response.derived())
				.filter(response -> response.subjectRef() != null)
				.toList();
		List<Response> flatResponses = responses.stream()
				.filter(response -> !response.derived())
				.filter(response -> response.subjectRef() == null)
				.toList();

		builder.append("- Summary: ").append(escape(summaryLine(rollup, detailResponses, flatResponses))).append('\n');
		if (rollup != null && !rollup.contributingSubjectRefs().isEmpty()) {
			builder.append("- Contributing subjects: ")
					.append(rollup.contributingSubjectRefs().stream()
							.map(StructuredMarkdownReportExporter::selectorLabel)
							.collect(Collectors.joining(", ")))
					.append('\n');
		}
		if (detailResponses.isEmpty()) {
			appendFlatResponses(builder, flatResponses.isEmpty() && rollup != null ? List.of(rollup) : flatResponses);
			builder.append('\n');
			return;
		}

		builder.append('\n')
				.append("<details>\n")
				.append("<summary>")
				.append(escape(detailResponses.size() + " structured detail assertion(s)"))
				.append("</summary>\n\n");
		for (Response detail : orderDetails(detailResponses, rollup)) {
			appendDetailResponse(builder, detail);
		}
		builder.append("</details>\n\n");
	}

	/**
	 * Appends one bullet per flat response.
	 *
	 * @param builder the report being built; appended to in place
	 * @param responses the flat responses to render
	 */
	private static void appendFlatResponses(StringBuilder builder, List<Response> responses) {
		for (Response response : responses) {
			builder.append("  - ")
					.append(escape(responseLine(response)))
					.append('\n');
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
		builder.append("- Subject `")
				.append(escape(response.subjectRef().relationName()))
				.append("` at `")
				.append(escape(selectorLabel(response.subjectRef())))
				.append("`: ")
				.append(escape(responseLine(response)))
				.append('\n');
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
		builder.append("## Aggregate and unresolved responses\n\n");
		for (Response response : responses) {
			builder.append("- `")
					.append(escape(response.recordId()))
					.append("` · `")
					.append(escape(response.testId()))
					.append("` · ")
					.append(escape(responseLine(response)))
					.append('\n');
		}
		builder.append('\n');
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
	 * Renders a display label for a structured selector.
	 *
	 * @param subjectRef the structured subject reference to render
	 * @return a concise source-location-plus-selector label
	 */
	private static String selectorLabel(SubjectRef subjectRef) {
		String source = subjectRef.sourceLocation() == null || subjectRef.sourceLocation().isBlank()
				? "<unknown source>"
				: subjectRef.sourceLocation();
		String row = subjectRef.rowRef() == null || subjectRef.rowRef().isBlank()
				? "<selector unavailable>"
				: selectorValue(subjectRef.rowRef());
		return source + "#" + row;
	}

	/**
	 * Converts a stored row reference into a row selector string.
	 *
	 * @param rowRef the stored provenance row reference
	 * @return a position-style row selector when possible, otherwise a row-ref selector
	 */
	private static String selectorValue(String rowRef) {
		Matcher matcher = SYNTHETIC_ROW_REF_PATTERN.matcher(rowRef);
		if (matcher.matches()) {
			return "row=" + matcher.group(1);
		}
		return "rowRef=" + rowRef;
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
