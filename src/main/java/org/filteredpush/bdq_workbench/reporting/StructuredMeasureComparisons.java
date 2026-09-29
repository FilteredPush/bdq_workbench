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
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

/**
 * Shared helpers for comparing pre-amendment and post-amendment multi-record measures.
 *
 * <p>The HTML and Markdown structured reports both summarize the same built-in multi-record
 * MEASURE outputs. This helper derives one deterministic comparison row per measure so those
 * reports stay synchronized while presenting the results differently.
 */
final class StructuredMeasureComparisons {

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
			Response pre = byPhase.get(Phase.PRE_AMENDMENT);
			Response post = byPhase.get(Phase.POST_AMENDMENT);
			Integer prePercent = extractMeasurePercentage(pre);
			Integer postPercent = extractMeasurePercentage(post);
			String preText = renderMeasurePhaseText(pre);
			String postText = renderMeasurePhaseText(post);
			Integer deltaPercent = computeDeltaPercent(prePercent, postPercent);
			comparisons.add(new MeasureComparison(
					example.parameters().getOrDefault(BuiltInMeasureSpec.MEASURE_LABEL_KEY, example.testId()),
					preText,
					prePercent,
					postText,
					postPercent,
					renderMeasureChangeText(preText, postText, deltaPercent),
					deltaPercent,
					hasDifference(preText, postText, deltaPercent)));
		}
		comparisons.sort(java.util.Comparator.comparing(MeasureComparison::label, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(comparisons);
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
	 * @param preText the rendered pre-amendment text
	 * @param postText the rendered post-amendment text
	 * @param deltaPercent the rounded percentage delta, when available
	 * @return the human-readable change summary
	 */
	private static String renderMeasureChangeText(String preText, String postText, Integer deltaPercent) {
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
	 * Reports whether a measure changed between the pre-amendment and post-amendment phases.
	 *
	 * @param preText the rendered pre-amendment text
	 * @param postText the rendered post-amendment text
	 * @param deltaPercent the rounded percentage delta, when available
	 * @return {@code true} when the measure changed
	 */
	private static boolean hasDifference(String preText, String postText, Integer deltaPercent) {
		return deltaPercent != null ? deltaPercent != 0 : !Objects.equals(preText, postText);
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
	 * @param label the rendered measure label
	 * @param preText the textual pre-amendment summary
	 * @param prePercent the rounded pre-amendment percentage, when available
	 * @param postText the textual post-amendment summary
	 * @param postPercent the rounded post-amendment percentage, when available
	 * @param changeText the rendered change summary
	 * @param deltaPercent the signed percentage delta, when available
	 * @param changed whether the pre/post values differ
	 */
	record MeasureComparison(
			String label,
			String preText,
			Integer prePercent,
			String postText,
			Integer postPercent,
			String changeText,
			Integer deltaPercent,
			boolean changed) {
	}
}
