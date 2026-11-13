/** MultiRecordMeasureSummaries.java
 *
 * Summarizes the built-in multi-record COUNT and QA measures of a run, before and after amendment,
 * for the GUI's overview charts.
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;

/**
 * One row per multi-record COUNT measure: the share of records with its counted outcome before and
 * after amendment, its data quality dimension, and the result of the QA measure on the same target
 * test. A COUNT measure's value is a count of records, compared as a percentage; a QA measure's
 * value is its result, COMPLETE or NOT_COMPLETE, and is never shown as a rate.
 *
 * <p>Rows are ordered by the change from pre- to post-amendment, greatest improvement first; then
 * unchanged measures; then measures that got worse; then measures without both phases.
 */
public final class MultiRecordMeasureSummaries {

	/** Result of a QA measure for a dataset meeting its criteria. */
	public static final String COMPLETE = "COMPLETE";

	/**
	 * One phase of a COUNT measure.
	 *
	 * @param count records with the counted outcome
	 * @param total records assessed
	 */
	public record PhaseValue(long count, long total) {
		/**
		 * @return the share of records with the counted outcome, 0 to 100
		 */
		public double percent() {
			return total <= 0 ? 0d : count * 100d / total;
		}
	}

	/**
	 * One multi-record COUNT measure, with its paired QA measure.
	 *
	 * @param testId the COUNT measure's test ID
	 * @param label the COUNT measure's label
	 * @param targetLabel the label of the validation it counts, used as the row label
	 * @param dimension the COUNT measure's data quality dimension, or "" if unknown
	 * @param pre the pre-amendment value, or null if absent
	 * @param post the post-amendment value, or null if absent
	 * @param qaPre the paired QA measure's pre-amendment result, or null if absent
	 * @param qaPost the paired QA measure's post-amendment result, or null if absent
	 */
	public record Row(
			String testId,
			String label,
			String targetLabel,
			String dimension,
			PhaseValue pre,
			PhaseValue post,
			String qaPre,
			String qaPost) {
		/**
		 * @return true if both phases are present
		 */
		public boolean hasBothPhases() {
			return pre != null && post != null;
		}

		/**
		 * @return the change in percentage points from pre- to post-amendment, 0 without both phases
		 */
		public double change() {
			return hasBothPhases() ? post.percent() - pre.percent() : 0d;
		}
	}

	/**
	 * The summary of a run's multi-record measures.
	 *
	 * @param rows the COUNT measures, greatest improvement first
	 * @param qaMeasures the number of QA measures with a result in either phase
	 * @param qaCompletePre QA measures COMPLETE pre-amendment
	 * @param qaWithPre QA measures with a pre-amendment result
	 * @param qaCompletePost QA measures COMPLETE post-amendment
	 * @param qaWithPost QA measures with a post-amendment result
	 */
	public record Summary(
			List<Row> rows,
			int qaMeasures,
			int qaCompletePre,
			int qaWithPre,
			int qaCompletePost,
			int qaWithPost) {
		/**
		 * @return COUNT measures that improved after amendment
		 */
		public long improved() {
			return rows.stream().filter(Row::hasBothPhases).filter(row -> row.change() > EPSILON).count();
		}

		/**
		 * @return COUNT measures unchanged by amendment
		 */
		public long unchanged() {
			return rows.stream().filter(Row::hasBothPhases).filter(row -> Math.abs(row.change()) <= EPSILON).count();
		}

		/**
		 * @return COUNT measures that got worse after amendment
		 */
		public long worse() {
			return rows.stream().filter(Row::hasBothPhases).filter(row -> row.change() < -EPSILON).count();
		}

		/**
		 * @param phase PRE_AMENDMENT or POST_AMENDMENT
		 * @return the median percentage of the COUNT measures in that phase, or NaN if none
		 */
		public double median(Phase phase) {
			List<Double> values = rows.stream()
					.map(row -> phase == Phase.POST_AMENDMENT ? row.post() : row.pre())
					.filter(value -> value != null)
					.map(PhaseValue::percent)
					.sorted()
					.toList();
			if (values.isEmpty()) {
				return Double.NaN;
			}
			int middle = values.size() / 2;
			return values.size() % 2 == 1 ? values.get(middle) : (values.get(middle - 1) + values.get(middle)) / 2d;
		}

		/**
		 * @return true if there is nothing to show
		 */
		public boolean isEmpty() {
			return rows.isEmpty() && qaMeasures == 0;
		}
	}

	/** Changes smaller than this, in percentage points, count as unchanged. */
	static final double EPSILON = 1e-9;

	private MultiRecordMeasureSummaries() {
	}

	/**
	 * Summarizes the multi-record measure responses of a run.
	 *
	 * @param responses the run's responses
	 * @param useCaseTests the tests of the use case's policy, for the measures' dimensions
	 * @return the summary
	 */
	public static Summary build(List<Response> responses, List<TestDefinition> useCaseTests) {
		Map<String, String> dimensionByTestId = new LinkedHashMap<>();
		for (TestDefinition test : useCaseTests) {
			String dimension = test.metadata() == null ? null
					: test.metadata().get(RdfPolicyResolverService.TEST_METADATA_DIMENSION);
			if (dimension != null) {
				dimensionByTestId.put(test.id(), dimension);
			}
		}
		Map<String, Map<Phase, Response>> counts = new LinkedHashMap<>();
		Map<String, Map<Phase, Response>> qaByTarget = new LinkedHashMap<>();
		Map<String, Map<Phase, Response>> qaByTest = new LinkedHashMap<>();
		for (Response response : responses) {
			if (response.testType() != TestType.MEASURE || !"MULTIRECORD".equals(response.recordId())) {
				continue;
			}
			String kind = response.parameters().get(BuiltInMeasureSpec.KIND_KEY);
			if (BuiltInMeasureSpec.MeasureKind.COUNT.name().equals(kind)) {
				counts.computeIfAbsent(response.testId(), ignored -> new LinkedHashMap<>()).put(response.phase(), response);
			} else if (BuiltInMeasureSpec.MeasureKind.QA.name().equals(kind)) {
				qaByTest.computeIfAbsent(response.testId(), ignored -> new LinkedHashMap<>()).put(response.phase(), response);
				String target = response.parameters().get(BuiltInMeasureSpec.TARGET_TEST_ID_KEY);
				if (target != null) {
					qaByTarget.computeIfAbsent(target, ignored -> new LinkedHashMap<>()).put(response.phase(), response);
				}
			}
		}
		List<Row> rows = new ArrayList<>();
		for (Map.Entry<String, Map<Phase, Response>> entry : counts.entrySet()) {
			Map<Phase, Response> byPhase = entry.getValue();
			Response example = byPhase.values().iterator().next();
			String label = example.parameters().getOrDefault(BuiltInMeasureSpec.MEASURE_LABEL_KEY, entry.getKey());
			String targetLabel = example.parameters().getOrDefault(BuiltInMeasureSpec.TARGET_LABEL_KEY, label);
			String target = example.parameters().get(BuiltInMeasureSpec.TARGET_TEST_ID_KEY);
			Map<Phase, Response> qa = target == null ? Map.of() : qaByTarget.getOrDefault(target, Map.of());
			rows.add(new Row(
					entry.getKey(),
					label,
					targetLabel,
					dimensionByTestId.getOrDefault(entry.getKey(), ""),
					phaseValue(byPhase.get(Phase.PRE_AMENDMENT)),
					phaseValue(byPhase.get(Phase.POST_AMENDMENT)),
					qaResult(qa.get(Phase.PRE_AMENDMENT)),
					qaResult(qa.get(Phase.POST_AMENDMENT))));
		}
		rows.sort(Comparator
				.comparingInt((Row row) -> rank(row))
				.thenComparing(Comparator.comparingDouble(Row::change).reversed())
				.thenComparing(Row::targetLabel, String.CASE_INSENSITIVE_ORDER));
		int qaCompletePre = 0;
		int qaWithPre = 0;
		int qaCompletePost = 0;
		int qaWithPost = 0;
		for (Map<Phase, Response> byPhase : qaByTest.values()) {
			String pre = qaResult(byPhase.get(Phase.PRE_AMENDMENT));
			String post = qaResult(byPhase.get(Phase.POST_AMENDMENT));
			if (pre != null) {
				qaWithPre++;
				qaCompletePre += COMPLETE.equals(pre) ? 1 : 0;
			}
			if (post != null) {
				qaWithPost++;
				qaCompletePost += COMPLETE.equals(post) ? 1 : 0;
			}
		}
		return new Summary(List.copyOf(rows), qaByTest.size(), qaCompletePre, qaWithPre, qaCompletePost, qaWithPost);
	}

	/** Improved first, then unchanged, then worse, then rows without both phases. */
	private static int rank(Row row) {
		if (!row.hasBothPhases()) {
			return 3;
		}
		if (row.change() > EPSILON) {
			return 0;
		}
		return row.change() < -EPSILON ? 2 : 1;
	}

	private static PhaseValue phaseValue(Response response) {
		if (response == null) {
			return null;
		}
		String count = response.parameters().get(BuiltInMeasureSpec.MATCHING_COUNT_KEY);
		String total = response.parameters().get(BuiltInMeasureSpec.TOTAL_RECORDS_KEY);
		try {
			if (count != null && total != null) {
				return new PhaseValue(Long.parseLong(count.trim()), Long.parseLong(total.trim()));
			}
		} catch (NumberFormatException e) {
			return null;
		}
		return null;
	}

	private static String qaResult(Response response) {
		if (response == null || response.responseResult() == null || response.responseResult().isBlank()) {
			return null;
		}
		return response.responseResult().trim();
	}
}
