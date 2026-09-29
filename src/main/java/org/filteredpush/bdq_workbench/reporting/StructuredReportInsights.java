/** StructuredReportInsights.java
 *
 * Aggregates metadata and high-impact improvement cues for structured human-readable reports.
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * Aggregates run metadata and prioritized improvement cues for structured reports.
 *
 * <p>These insights are intentionally coarse and deterministic: they use response-level rollups
 * when available so structured runs are summarized at core-record granularity, fall back to direct
 * flat responses otherwise, and rank likely improvement opportunities by simple observed
 * frequency.
 */
record StructuredReportInsights(
		Instant runStartedAt,
		Instant runFinishedAt,
		long confirmedIssueCount,
		long potentialIssueCount,
		long validationNonComplianceCount,
		long recordsWithValidationNonCompliance,
		List<RankedInsight> topValidationNonComplianceCauses,
		List<RankedInsight> topAmendmentProposals) {

	private static final int TOP_INSIGHT_LIMIT = 5;
	private static final Set<String> SUCCESSFUL_AMENDMENT_STATUSES = Set.of("AMENDED", "FILLED_IN");
	private static final Set<String> PER_RECORD_SENTINELS = Set.of("MULTIRECORD", "*");

	/**
	 * Builds report insights from one execution summary.
	 *
	 * @param summary the execution summary to analyze
	 * @return the aggregated report insights
	 */
	static StructuredReportInsights from(ExecutionSummary summary) {
		Instant startedAt = summary.responses().stream()
				.map(Response::startedAt)
				.filter(Objects::nonNull)
				.min(Comparator.naturalOrder())
				.orElse(null);
		Instant finishedAt = summary.responses().stream()
				.map(Response::finishedAt)
				.filter(Objects::nonNull)
				.max(Comparator.naturalOrder())
				.orElse(null);

		List<Response> primaryValidationResponses = primaryPerRecordResponses(summary, TestType.VALIDATION);
		List<Response> primaryIssueResponses = primaryPerRecordResponses(summary, TestType.ISSUE);
		List<Response> directValidationResponses = summary.responses().stream()
				.filter(response -> response.testType() == TestType.VALIDATION)
				.filter(response -> !response.derived())
				.filter(response -> isPerRecordId(response.recordId()))
				.toList();
		Set<String> nonCompliantRecordIds = primaryValidationResponses.stream()
				.filter(response -> "NOT_COMPLIANT".equals(response.responseResult()))
				.map(Response::recordId)
				.filter(StructuredReportInsights::isPerRecordId)
				.collect(Collectors.toCollection(LinkedHashSet::new));

		return new StructuredReportInsights(
				startedAt,
				finishedAt,
				primaryIssueResponses.stream().filter(response -> "IS_ISSUE".equals(response.responseResult())).count(),
				primaryIssueResponses.stream().filter(response -> "POTENTIAL_ISSUE".equals(response.responseResult())).count(),
				primaryValidationResponses.stream().filter(response -> "NOT_COMPLIANT".equals(response.responseResult())).count(),
				nonCompliantRecordIds.size(),
				rankValidationCauses(summary, directValidationResponses),
				rankAmendmentProposals(summary, nonCompliantRecordIds));
	}

	/**
	 * Chooses per-record summary responses for one test type.
	 *
	 * <p>When a record/test/phase group has a derived rollup, that rollup represents the group in
	 * the summary; otherwise all direct responses in the group are kept, which preserves flat runs
	 * and unrolled response types.
	 *
	 * @param summary the execution summary supplying responses
	 * @param testType the test type to keep
	 * @return the selected summary responses
	 */
	private static List<Response> primaryPerRecordResponses(ExecutionSummary summary, TestType testType) {
		Map<ResponseGroupKey, List<Response>> grouped = new LinkedHashMap<>();
		for (Response response : summary.responses()) {
			if (response.testType() != testType || !isPerRecordId(response.recordId())) {
				continue;
			}
			grouped.computeIfAbsent(ResponseGroupKey.of(response), ignored -> new ArrayList<>()).add(response);
		}
		List<Response> primary = new ArrayList<>();
		for (List<Response> responses : grouped.values()) {
			List<Response> derived = responses.stream().filter(Response::derived).toList();
			if (!derived.isEmpty()) {
				primary.addAll(derived);
				continue;
			}
			primary.addAll(responses.stream().filter(response -> !response.derived()).toList());
		}
		return primary;
	}

	/**
	 * Ranks validation non-compliance causes by observed frequency.
	 *
	 * @param summary the execution summary supplying labels
	 * @param validationResponses the selected validation summary responses
	 * @return the ranked non-compliance causes
	 */
	private static List<RankedInsight> rankValidationCauses(
			ExecutionSummary summary,
			List<Response> validationResponses) {
		Map<String, InsightAccumulator> counts = new LinkedHashMap<>();
		for (Response response : validationResponses) {
			if (!"NOT_COMPLIANT".equals(response.responseResult())) {
				continue;
			}
			String label = displayTestLabel(summary, response);
			String explanation = normalize(firstNonBlank(response.comment(), response.message()));
			String cause = explanation == null || explanation.equals(label)
					? label
					: label + " — " + explanation;
			counts.computeIfAbsent(cause, ignored -> new InsightAccumulator())
					.add(response.recordId());
		}
		return rankInsights(counts);
	}

	/**
	 * Ranks amendment proposals seen on records with validation non-compliance findings.
	 *
	 * @param summary the execution summary supplying labels
	 * @param nonCompliantRecordIds records that had validation non-compliance findings
	 * @return the ranked amendment proposals
	 */
	private static List<RankedInsight> rankAmendmentProposals(
			ExecutionSummary summary,
			Set<String> nonCompliantRecordIds) {
		Map<String, InsightAccumulator> counts = new LinkedHashMap<>();
		for (Response response : summary.responses()) {
			if (response.testType() != TestType.AMENDMENT
					|| !isPerRecordId(response.recordId())
					|| !nonCompliantRecordIds.contains(response.recordId())
					|| response.amendments().isEmpty()
					|| !SUCCESSFUL_AMENDMENT_STATUSES.contains(response.responseStatus())) {
				continue;
			}
			String proposal = displayTestLabel(summary, response) + ": " + response.amendments().entrySet().stream()
					.sorted(Map.Entry.comparingByKey())
					.map(entry -> entry.getKey() + " → " + entry.getValue())
					.collect(Collectors.joining(", "));
			counts.computeIfAbsent(proposal, ignored -> new InsightAccumulator())
					.add(response.recordId());
		}
		return rankInsights(counts);
	}

	/**
	 * Converts accumulated counts into a sorted, truncated ranking.
	 *
	 * @param accumulators counts keyed by insight label
	 * @return the ranked insight list
	 */
	private static List<RankedInsight> rankInsights(Map<String, InsightAccumulator> accumulators) {
		return accumulators.entrySet().stream()
				.map(entry -> new RankedInsight(
						entry.getKey(),
						entry.getValue().responseCount(),
						entry.getValue().recordCount()))
				.sorted(Comparator.comparingLong(RankedInsight::responseCount)
						.reversed()
						.thenComparing(RankedInsight::label, String.CASE_INSENSITIVE_ORDER))
				.limit(TOP_INSIGHT_LIMIT)
				.toList();
	}

	/**
	 * Resolves a human-readable label for one response's test.
	 *
	 * @param summary the execution summary supplying optional test labels
	 * @param response the response whose test should be labeled
	 * @return the best available test label
	 */
	private static String displayTestLabel(ExecutionSummary summary, Response response) {
		String label = summary.metadata().testLabelsById().get(response.testId());
		return label == null || label.isBlank() ? response.testId() : label;
	}

	/**
	 * Returns the first non-blank string from two candidates.
	 *
	 * @param first the first candidate
	 * @param second the fallback candidate
	 * @return the first non-blank candidate, or {@code null} when both are blank
	 */
	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second != null && !second.isBlank() ? second : null;
	}

	/**
	 * Normalizes whitespace for a rendered explanation.
	 *
	 * @param raw the raw string to normalize
	 * @return the normalized string, or {@code null} when absent
	 */
	private static String normalize(String raw) {
		return raw == null ? null : raw.replaceAll("\\s+", " ").trim();
	}

	/**
	 * Reports whether a record ID refers to one concrete per-record result.
	 *
	 * @param recordId the record ID to inspect
	 * @return {@code true} when the record ID is non-blank and not a sentinel
	 */
	private static boolean isPerRecordId(String recordId) {
		return recordId != null && !recordId.isBlank() && !PER_RECORD_SENTINELS.contains(recordId);
	}

	/**
	 * One ranked summary item for the high-impact action section.
	 *
	 * @param label the rendered insight label
	 * @param responseCount how many responses contributed to the insight
	 * @param recordCount how many distinct core records contributed to the insight
	 */
	record RankedInsight(String label, long responseCount, long recordCount) {
	}

	/**
	 * Mutable accumulator for one ranked insight.
	 */
	private static final class InsightAccumulator {
		private long responseCount;
		private final Set<String> recordIds = new LinkedHashSet<>();

		private void add(String recordId) {
			responseCount++;
			if (recordId != null && !recordId.isBlank()) {
				recordIds.add(recordId);
			}
		}

		private long responseCount() {
			return responseCount;
		}

		private long recordCount() {
			return recordIds.size();
		}
	}

	/**
	 * Per-record response grouping key used to prefer derived rollups when available.
	 *
	 * @param recordId the core record ID
	 * @param testId the test identifier
	 * @param phaseName the phase name
	 */
	private record ResponseGroupKey(String recordId, String testId, String phaseName) {
		private static ResponseGroupKey of(Response response) {
			return new ResponseGroupKey(
					response.recordId(),
					response.testId(),
					response.phase() == null ? "" : response.phase().name());
		}
	}
}
