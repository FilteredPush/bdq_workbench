/** SingleRecordOutcomeHistograms.java
 *
 * Distributions, over the records assessed, of the single-record measures that count each record's
 * VALIDATION or AMENDMENT outcomes.
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SingleRecordOutcomeMeasureSpec;
import org.filteredpush.bdq_workbench.model.SingleRecordOutcomeMeasureSpec.Tally;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * Bins the values of the {@link SingleRecordOutcomeMeasureSpec} measures over the records assessed.
 *
 * <p>Each measure gives, for every record, the number of distinct VALIDATION (or AMENDMENT) tests
 * run on it with one outcome. A histogram has one bin for each possible value, 0 to the number of
 * tests of that type in the use case, and one series per measure: the bar at value {@code k} stacks,
 * for each measure, the number of records for which that measure was {@code k}. There is one
 * histogram for the VALIDATION measures (MEASURE_VALIDATIONTESTS_COMPLIANT, _NOTCOMPLIANT and
 * _PREREQUISITESNOTMET) in each of the pre- and post-amendment phases, and one for
 * MEASURE_AMENDMENTS_PROPOSED in the amendment phase. The pre- and post-amendment VALIDATION
 * histograms share their axes, so they can be compared: the x axis runs to the larger of their
 * largest values, and the y axis to their tallest bar, rounded up to a multiple of
 * {@link #Y_AXIS_STEP}. Only RUN_HAS_RESULT responses with an
 * integer result are binned; the others (ERROR for a record on which a counted test failed) are
 * counted as not binned.
 *
 * <p>A measure response is attributed to its measure by its test, recognized as for binding
 * ({@link SingleRecordOutcomeMeasureSpec#from(TestDefinition)}), or else by the tally recorded on a
 * built-in binding, so responses of discovered implementations of these measures are included too.
 */
public final class SingleRecordOutcomeHistograms {

	/** The top of a histogram's y axis is a multiple of this. */
	public static final int Y_AXIS_STEP = 5;

	/**
	 * One measure's distribution within a histogram.
	 *
	 * @param tally the measure
	 * @param counts the number of records with each value, indexed by value
	 */
	public record Series(Tally tally, int[] counts) {
		/**
		 * @return the number of records binned in this series
		 */
		public int total() {
			int total = 0;
			for (int count : counts) {
				total += count;
			}
			return total;
		}
	}

	/**
	 * A stacked histogram of single-record outcome measures in one phase.
	 *
	 * @param countedType the type of test the measures count: VALIDATION or AMENDMENT
	 * @param phase the phase whose measure responses are binned
	 * @param testsInUseCase the number of tests of {@code countedType} in the use case
	 * @param maxValue the largest value on the axis: {@code testsInUseCase}, or a larger observed value
	 * @param series one series per measure present, in {@link Tally} order
	 * @param notBinned the number of measure responses that had no integer result (e.g. ERROR)
	 * @param yMax the top of the y axis: the tallest bar, rounded up to a multiple of
	 *     {@link #Y_AXIS_STEP}; shared by the pre- and post-amendment VALIDATION histograms
	 */
	public record Histogram(
			TestType countedType,
			Phase phase,
			int testsInUseCase,
			int maxValue,
			List<Series> series,
			int notBinned,
			int yMax) {
		/**
		 * @param value a value on the axis
		 * @return the bar height at {@code value}: the sum over the series
		 */
		public int stackedCount(int value) {
			int sum = 0;
			for (Series one : series) {
				sum += one.counts()[value];
			}
			return sum;
		}

		/**
		 * @return the tallest bar
		 */
		public int maxStackedCount() {
			int max = 0;
			for (int value = 0; value <= maxValue; value++) {
				max = Math.max(max, stackedCount(value));
			}
			return max;
		}

		/**
		 * @return a short title, e.g. "VALIDATION outcomes per record, pre-amendment"
		 */
		public String title() {
			String phaseText = switch (phase) {
				case PRE_AMENDMENT -> "pre-amendment";
				case AMENDMENT -> "amendment phase";
				case POST_AMENDMENT -> "post-amendment";
			};
			return countedType + " outcomes per record, " + phaseText;
		}
	}

	private SingleRecordOutcomeHistograms() {
	}

	/**
	 * Builds the histograms for a run: VALIDATION pre- and post-amendment, then AMENDMENT, each
	 * only if at least one of its measures produced a response.
	 *
	 * @param responses the run's responses
	 * @param useCaseTests the tests of the use case's policy
	 * @return the histograms, possibly empty
	 */
	public static List<Histogram> build(List<Response> responses, List<TestDefinition> useCaseTests) {
		Map<String, Tally> tallyByTestId = new LinkedHashMap<>();
		for (TestDefinition test : useCaseTests) {
			SingleRecordOutcomeMeasureSpec.from(test).ifPresent(spec -> tallyByTestId.put(test.id(), spec.tally()));
		}
		List<Histogram> validations = new ArrayList<>();
		histogram(TestType.VALIDATION, Phase.PRE_AMENDMENT, responses, useCaseTests, tallyByTestId).ifPresent(validations::add);
		histogram(TestType.VALIDATION, Phase.POST_AMENDMENT, responses, useCaseTests, tallyByTestId).ifPresent(validations::add);
		List<Histogram> histograms = new ArrayList<>(withSharedAxes(validations));
		histogram(TestType.AMENDMENT, Phase.AMENDMENT, responses, useCaseTests, tallyByTestId)
				.map(amendments -> withSharedAxes(List.of(amendments)).get(0))
				.ifPresent(histograms::add);
		return List.copyOf(histograms);
	}

	/**
	 * Gives histograms common axes: the x axis to the largest {@code maxValue} among them, and the
	 * y axis to their tallest bar, rounded up to a multiple of {@link #Y_AXIS_STEP}.
	 *
	 * @param histograms histograms to compare, e.g. pre- and post-amendment
	 * @return the same histograms with common axes
	 */
	static List<Histogram> withSharedAxes(List<Histogram> histograms) {
		int maxValue = histograms.stream().mapToInt(Histogram::maxValue).max().orElse(0);
		List<Histogram> widened = histograms.stream().map(histogram -> widen(histogram, maxValue)).toList();
		int tallest = widened.stream().mapToInt(Histogram::maxStackedCount).max().orElse(0);
		int yMax = Math.max(Y_AXIS_STEP, (tallest + Y_AXIS_STEP - 1) / Y_AXIS_STEP * Y_AXIS_STEP);
		return widened.stream()
				.map(histogram -> new Histogram(histogram.countedType(), histogram.phase(), histogram.testsInUseCase(),
						histogram.maxValue(), histogram.series(), histogram.notBinned(), yMax))
				.toList();
	}

	private static Histogram widen(Histogram histogram, int maxValue) {
		if (histogram.maxValue() == maxValue) {
			return histogram;
		}
		List<Series> series = histogram.series().stream()
				.map(one -> new Series(one.tally(), java.util.Arrays.copyOf(one.counts(), maxValue + 1)))
				.toList();
		return new Histogram(histogram.countedType(), histogram.phase(), histogram.testsInUseCase(), maxValue, series,
				histogram.notBinned(), histogram.yMax());
	}

	private static Optional<Histogram> histogram(
			TestType countedType,
			Phase phase,
			List<Response> responses,
			List<TestDefinition> useCaseTests,
			Map<String, Tally> tallyByTestId) {
		int testsInUseCase = (int) useCaseTests.stream()
				.filter(test -> test.type() == countedType)
				.map(TestDefinition::id)
				.distinct()
				.count();
		Map<Tally, List<Integer>> values = new EnumMap<>(Tally.class);
		int notBinned = 0;
		for (Response response : responses) {
			if (response.phase() != phase || response.testType() != TestType.MEASURE || response.derived()) {
				continue;
			}
			Tally tally = tallyOf(response, tallyByTestId);
			if (tally == null || tally.countedType() != countedType) {
				continue;
			}
			Integer value = integerResult(response);
			if (value == null) {
				notBinned++;
				continue;
			}
			values.computeIfAbsent(tally, ignored -> new ArrayList<>()).add(value);
		}
		if (values.isEmpty() && notBinned == 0) {
			return Optional.empty();
		}
		int maxValue = testsInUseCase;
		for (List<Integer> list : values.values()) {
			for (int value : list) {
				maxValue = Math.max(maxValue, value);
			}
		}
		List<Series> series = new ArrayList<>();
		for (Map.Entry<Tally, List<Integer>> entry : values.entrySet()) {
			int[] counts = new int[maxValue + 1];
			entry.getValue().forEach(value -> counts[value]++);
			series.add(new Series(entry.getKey(), counts));
		}
		return Optional.of(new Histogram(countedType, phase, testsInUseCase, maxValue, List.copyOf(series), notBinned, 0));
	}

	private static Tally tallyOf(Response response, Map<String, Tally> tallyByTestId) {
		Tally tally = tallyByTestId.get(response.testId());
		if (tally != null) {
			return tally;
		}
		String recorded = response.parameters().get(SingleRecordOutcomeMeasureSpec.TALLY_KEY);
		if (recorded == null) {
			return null;
		}
		try {
			return Tally.valueOf(recorded);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static Integer integerResult(Response response) {
		if (!"RUN_HAS_RESULT".equals(response.responseStatus()) || response.responseResult() == null) {
			return null;
		}
		try {
			int value = Integer.parseInt(response.responseResult().trim());
			return value < 0 ? null : value;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
