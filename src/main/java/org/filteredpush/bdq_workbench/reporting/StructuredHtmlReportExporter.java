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
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SubjectRef;

/**
 * Exports a human-readable HTML report for flat and structured results.
 *
 * <p>The rendered report keeps one top-level section per core record. For flat runs, each test
 * group renders as a simplified single-level summary. For structured runs, each test group renders
 * a core-record-level summary followed by nested detail assertions, including source-row selectors
 * derived from each contributing {@link SubjectRef}. The exporter complements the existing summary,
 * tab-delimited, RDF, XLSX, and Markdown outputs rather than replacing them.
 */
public class StructuredHtmlReportExporter implements ReportExporter {

	private static final String MULTIRECORD_SENTINEL = "MULTIRECORD";
	private static final String UNRESOLVED_SENTINEL = "*";

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
				.append("  </style>\n")
				.append("</head>\n")
				.append("<body>\n")
				.append("<h1>BDQ Workbench Structured Report</h1>\n")
				.append("<p>Use case: ")
				.append(escapeHtml(describeUseCase(summary)))
				.append("</p>\n");
		for (Map.Entry<String, List<Response>> entry : responsesByRecord.entrySet()) {
			appendRecordSection(builder, entry.getKey(), entry.getValue(), recordsById.get(entry.getKey()));
		}
		appendAggregateSection(builder, aggregateResponses);
		builder.append("</body>\n</html>\n");
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
