/** StructuredMeasureComparisons.java
 *
 * Shared measure-comparison summaries for structured human-readable reports.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec.MeasureKind;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

/**
 * Shared helpers for comparing pre-amendment and post-amendment multi-record measures.
 *
 * <p>The HTML and Markdown structured reports both summarize the same built-in multi-record
 * MEASURE outputs. This helper derives one deterministic comparison row per measure so those
 * reports stay synchronized while presenting the results differently.
 *
 * <p>The two kinds of measure are compared differently. A COUNT measure's value is a count of
 * records, compared as a percentage of the records. A QA measure's value is its
 * {@code Response.result}, COMPLETE or NOT_COMPLETE for the dataset as a whole; the share of
 * records that met its criteria is kept only as a supplementary pass-rate note, never as the
 * measure's value or as the basis for deciding whether it changed.
 */
final class StructuredMeasureComparisons {
	/** QA measure result when every record met the measure's criteria. */
	static final String COMPLETE = "COMPLETE";
	/** QA measure result when some record did not meet the measure's criteria. */
	static final String NOT_COMPLETE = "NOT_COMPLETE";

	/** Not instantiable: a holder for static summarizing logic. */
	private StructuredMeasureComparisons() {
	}

	/**
	 * Builds one ordered pre/post comparison per emitted multi-record measure.
	 *
	 * @param summary the execution summary supplying multi-record measure responses
	 * @return the ordered measure comparisons to report
	 */
	static List<MeasureComparison> summarize(ExecutionSummary summary) {
		List<MeasureComparison> comparisons = new ArrayList<>();
		for (Map<Phase, Response> byPhase : summary.multiRecordMeasureResponsesByTestAndPhase().values()) {
			Response example = byPhase.values().stream().findFirst().orElse(null);
			if (example == null) {
				continue;
			}
			String label = example.parameters().getOrDefault(BuiltInMeasureSpec.MEASURE_LABEL_KEY, example.testId());
			Response pre = byPhase.get(Phase.PRE_AMENDMENT);
			Response post = byPhase.get(Phase.POST_AMENDMENT);
			comparisons.add(isQa(example) ? qaComparison(label, pre, post) : countComparison(label, pre, post));
		}
		comparisons.sort(java.util.Comparator.comparing(MeasureComparison::label, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(comparisons);
	}

	/**
	 * Selects the comparisons of one measure kind, keeping their order.
	 *
	 * @param comparisons the comparisons from {@link #summarize}
	 * @param kind the kind to keep
	 * @return the comparisons of that kind
	 */
	static List<MeasureComparison> ofKind(List<MeasureComparison> comparisons, MeasureKind kind) {
		return comparisons.stream().filter(comparison -> comparison.kind() == kind).toList();
	}

	/**
	 * Reports whether a measure response belongs to a QA measure.
	 *
	 * @param response a measure response
	 * @return {@code true} if its recorded kind is {@link MeasureKind#QA}
	 */
	private static boolean isQa(Response response) {
		return MeasureKind.QA.name().equals(response.parameters().get(BuiltInMeasureSpec.KIND_KEY));
	}

	/**
	 * Compares a COUNT measure's phases by the percentage of records counted.
	 *
	 * @param label the measure label
	 * @param pre the pre-amendment response, or {@code null}
	 * @param post the post-amendment response, or {@code null}
	 * @return the comparison
	 */
	private static MeasureComparison countComparison(String label, Response pre, Response post) {
		Integer prePercent = extractMeasurePercentage(pre);
		Integer postPercent = extractMeasurePercentage(post);
		String preText = renderCountPhaseText(pre);
		String postText = renderCountPhaseText(post);
		Integer deltaPercent = computeDeltaPercent(prePercent, postPercent);
		return new MeasureComparison(
				MeasureKind.COUNT,
				label,
				preText,
				prePercent,
				null,
				postText,
				postPercent,
				null,
				renderCountChangeText(preText, postText, deltaPercent),
				deltaPercent,
				deltaPercent != null ? deltaPercent != 0 : !Objects.equals(preText, postText));
	}

	/**
	 * Compares a QA measure's phases by their results, with each phase's pass rate as a note.
	 *
	 * @param label the measure label
	 * @param pre the pre-amendment response, or {@code null}
	 * @param post the post-amendment response, or {@code null}
	 * @return the comparison
	 */
	private static MeasureComparison qaComparison(String label, Response pre, Response post) {
		String preText = renderResultText(pre);
		String postText = renderResultText(post);
		boolean changed = !Objects.equals(preText, postText);
		return new MeasureComparison(
				MeasureKind.QA,
				label,
				preText,
				null,
				passRate(pre),
				postText,
				null,
				passRate(post),
				changed ? preText + " → " + postText : "No observed change",
				null,
				changed);
	}

	/**
	 * Renders a COUNT measure phase as its count, total, and percentage.
	 *
	 * @param response the phase response, or {@code null} if that phase was not run
	 * @return the textual summary for the phase
	 */
	private static String renderCountPhaseText(Response response) {
		if (response == null) {
			return "not run";
		}
		String count = response.parameters().getOrDefault(BuiltInMeasureSpec.MATCHING_COUNT_KEY, response.responseResult());
		String total = response.parameters().getOrDefault(BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "?");
		String percentage = response.parameters().get(BuiltInMeasureSpec.PERCENTAGE_KEY);
		return percentage == null || percentage.isBlank()
				? count + "/" + total
				: count + "/" + total + " (" + percentage + "%)";
	}

	/**
	 * Renders a phase as its response result, falling back to its response status.
	 *
	 * @param response the phase response, or {@code null} if that phase was not run
	 * @return the textual summary for the phase
	 */
	private static String renderResultText(Response response) {
		if (response == null) {
			return "not run";
		}
		return firstNonBlank(response.responseResult(), response.responseStatus(), "not run");
	}

	/**
	 * Describes the share of records that met a QA measure's criteria, in the terms of the target
	 * test's responses, e.g. {@code "87% of records were COMPLIANT (870 of 1000)"}.
	 *
	 * <p>The percentage is rounded down, so a NOT_COMPLETE measure never reads as 100%.
	 *
	 * @param response the QA measure's phase response, or {@code null}
	 * @return the pass-rate note, or {@code null} when the response has no result or no counts
	 */
	private static String passRate(Response response) {
		if (response == null
				|| !(COMPLETE.equals(response.responseResult()) || NOT_COMPLETE.equals(response.responseResult()))) {
			return null;
		}
		String matching = response.parameters().get(BuiltInMeasureSpec.MATCHING_COUNT_KEY);
		String total = response.parameters().get(BuiltInMeasureSpec.TOTAL_RECORDS_KEY);
		List<String> criteria = new ArrayList<>(split(response.parameters().get(BuiltInMeasureSpec.ACCEPTABLE_RESPONSE_RESULTS_KEY)));
		criteria.addAll(split(response.parameters().get(BuiltInMeasureSpec.ACCEPTABLE_RESPONSE_STATUSES_KEY)));
		try {
			long matchingCount = Long.parseLong(matching);
			long totalCount = Long.parseLong(total);
			if (totalCount <= 0 || criteria.isEmpty()) {
				return null;
			}
			long percent = matchingCount * 100 / totalCount;
			return percent + "% of records were " + String.join(" or ", criteria)
					+ " (" + matchingCount + " of " + totalCount + ")";
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Splits a pipe-delimited binding parameter value into its trimmed, non-blank tokens.
	 *
	 * @param value the value, possibly null
	 * @return the tokens
	 */
	private static List<String> split(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return java.util.Arrays.stream(value.split("\\|"))
				.map(String::trim)
				.filter(token -> !token.isEmpty())
				.toList();
	}

	/**
	 * Extracts a whole-number percentage for a COUNT measure phase when one is available.
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
	 * Renders one concise change summary for a COUNT measure's pre/post pair.
	 *
	 * @param preText the rendered pre-amendment text
	 * @param postText the rendered post-amendment text
	 * @param deltaPercent the rounded percentage delta, when available
	 * @return the human-readable change summary
	 */
	private static String renderCountChangeText(String preText, String postText, Integer deltaPercent) {
		if (deltaPercent != null) {
			if (deltaPercent == 0) {
				return "No percentage-point change";
			}
			return (deltaPercent > 0 ? "+" : "") + deltaPercent + " percentage point(s)";
		}
		return Objects.equals(preText, postText)
				? "No observed change"
				: preText + " → " + postText;
	}

	/**
	 * Computes the percentage delta for a measure when both phases carry percentages.
	 *
	 * @param prePercent the rounded pre-amendment percentage, when available
	 * @param postPercent the rounded post-amendment percentage, when available
	 * @return the signed delta, or {@code null} when unavailable
	 */
	private static Integer computeDeltaPercent(Integer prePercent, Integer postPercent) {
		return prePercent == null || postPercent == null ? null : postPercent - prePercent;
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
	 * One pre/post comparison row for structured measure reporting.
	 *
	 * @param kind whether this is a COUNT or a QA measure
	 * @param label the rendered measure label
	 * @param preText the textual pre-amendment summary (for a QA measure, its result)
	 * @param prePercent the rounded pre-amendment percentage of a COUNT measure; {@code null} for QA
	 * @param prePassRate the pre-amendment pass-rate note of a QA measure, when available
	 * @param postText the textual post-amendment summary (for a QA measure, its result)
	 * @param postPercent the rounded post-amendment percentage of a COUNT measure; {@code null} for QA
	 * @param postPassRate the post-amendment pass-rate note of a QA measure, when available
	 * @param changeText the rendered change summary
	 * @param deltaPercent the signed percentage delta of a COUNT measure, when available
	 * @param changed whether the pre/post values differ (for a QA measure, whether its result did)
	 */
	record MeasureComparison(
			MeasureKind kind,
			String label,
			String preText,
			Integer prePercent,
			String prePassRate,
			String postText,
			Integer postPercent,
			String postPassRate,
			String changeText,
			Integer deltaPercent,
			boolean changed) {
	}
}
