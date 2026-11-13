package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SingleRecordOutcomeMeasureSpec.Tally;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.reporting.SingleRecordOutcomeHistograms.Histogram;
import org.junit.jupiter.api.Test;

/** Tests the binning of the single-record outcome measures over the records assessed. */
class SingleRecordOutcomeHistogramsTest {
	private static final String COMPLIANT = "https://rs.tdwg.org/bdqtest/terms/45fb49eb-4a1b-4b49-876f-15d5034dfc73-2026-11-12";
	private static final String NOT_COMPLIANT = "https://rs.tdwg.org/bdqtest/terms/453844ae-9df4-439f-8e24-c52498eca84a-2026-11-12";
	private static final String AMENDMENTS = "https://rs.tdwg.org/bdqtest/terms/03049fe5-a575-404f-b564-ae63f5a1cf8b-2026-11-12";

	/**
	 * Bins each measure's value per record, from 0 to the number of tests of the counted type in
	 * the use case, one histogram per phase, and counts responses without an integer result apart.
	 */
	@Test
	void binsEachMeasureOverTheRecordsPerPhase() {
		List<TestDefinition> tests = List.of(
				test("v1", TestType.VALIDATION), test("v2", TestType.VALIDATION), test("v3", TestType.VALIDATION),
				test("a1", TestType.AMENDMENT), test("a2", TestType.AMENDMENT),
				test(COMPLIANT, TestType.MEASURE), test(NOT_COMPLIANT, TestType.MEASURE), test(AMENDMENTS, TestType.MEASURE));
		List<Response> responses = new ArrayList<>();
		// pre-amendment: COMPLIANT 3, 2, 2; NOT_COMPLIANT 0, 1, ERROR
		responses.add(measure("r1", COMPLIANT, Phase.PRE_AMENDMENT, "3"));
		responses.add(measure("r2", COMPLIANT, Phase.PRE_AMENDMENT, "2"));
		responses.add(measure("r3", COMPLIANT, Phase.PRE_AMENDMENT, "2"));
		responses.add(measure("r1", NOT_COMPLIANT, Phase.PRE_AMENDMENT, "0"));
		responses.add(measure("r2", NOT_COMPLIANT, Phase.PRE_AMENDMENT, "1"));
		responses.add(error("r3", NOT_COMPLIANT, Phase.PRE_AMENDMENT));
		// post-amendment: COMPLIANT 3 for every record
		for (String record : List.of("r1", "r2", "r3")) {
			responses.add(measure(record, COMPLIANT, Phase.POST_AMENDMENT, "3"));
		}
		// amendment phase: 2, 0, 1 amendments proposed
		responses.add(measure("r1", AMENDMENTS, Phase.AMENDMENT, "2"));
		responses.add(measure("r2", AMENDMENTS, Phase.AMENDMENT, "0"));
		responses.add(measure("r3", AMENDMENTS, Phase.AMENDMENT, "1"));

		List<Histogram> histograms = SingleRecordOutcomeHistograms.build(responses, tests);

		assertThat(histograms).extracting(Histogram::countedType, Histogram::phase, Histogram::testsInUseCase,
				Histogram::maxValue, Histogram::notBinned)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(TestType.VALIDATION, Phase.PRE_AMENDMENT, 3, 3, 1),
						org.assertj.core.groups.Tuple.tuple(TestType.VALIDATION, Phase.POST_AMENDMENT, 3, 3, 0),
						org.assertj.core.groups.Tuple.tuple(TestType.AMENDMENT, Phase.AMENDMENT, 2, 2, 0));
		Histogram pre = histograms.get(0);
		assertThat(pre.series()).extracting(series -> series.tally()).containsExactly(Tally.COMPLIANT, Tally.NOT_COMPLIANT);
		assertThat(pre.series().get(0).counts()).containsExactly(0, 0, 2, 1);
		assertThat(pre.series().get(1).counts()).containsExactly(1, 1, 0, 0);
		assertThat(pre.stackedCount(0)).isEqualTo(1);
		assertThat(pre.stackedCount(2)).isEqualTo(2);
		assertThat(pre.maxStackedCount()).isEqualTo(2);
		assertThat(histograms.get(1).series().get(0).counts()).containsExactly(0, 0, 0, 3);
		assertThat(histograms.get(2).series().get(0).counts()).containsExactly(1, 1, 1);
		assertThat(histograms.get(2).title()).isEqualTo("AMENDMENT outcomes per record, amendment phase");
		// pre and post share the y axis: their tallest bar (pre: 2 at value 2; post: 3 at value 3), rounded up to 5
		assertThat(histograms.get(0).yMax()).isEqualTo(5);
		assertThat(histograms.get(1).yMax()).isEqualTo(5);
		assertThat(histograms.get(2).yMax()).isEqualTo(5);
	}

	/** Without any single-record outcome measure responses, there is no histogram. */
	@Test
	void noHistogramsWithoutMeasureResponses() {
		assertThat(SingleRecordOutcomeHistograms.build(List.of(), List.of(test("v1", TestType.VALIDATION)))).isEmpty();
	}

	private static TestDefinition test(String id, TestType type) {
		return new TestDefinition(id, null, type, type == TestType.AMENDMENT ? Phase.AMENDMENT : Phase.PRE_AMENDMENT,
				Map.of(), Map.of());
	}

	private static Response measure(String recordId, String testId, Phase phase, String result) {
		return new Response(recordId, testId, TestType.MEASURE, "impl", "method", phase, Map.of(), OutcomeStatus.PASSED,
				"RUN_HAS_RESULT", result, "", "", Map.of(), Instant.EPOCH, Instant.EPOCH);
	}

	private static Response error(String recordId, String testId, Phase phase) {
		return new Response(recordId, testId, TestType.MEASURE, "impl", "method", phase, Map.of(), OutcomeStatus.ERROR,
				"ERROR", null, "", "", Map.of(), Instant.EPOCH, Instant.EPOCH);
	}

	/**
	 * The pre- and post-amendment VALIDATION histograms share their y axis, the tallest bar of
	 * either rounded up to a multiple of 5, and their x axis, the larger of their largest values.
	 */
	@Test
	void validationHistogramsShareTheirAxes() {
		List<TestDefinition> tests = List.of(test("v1", TestType.VALIDATION), test(COMPLIANT, TestType.MEASURE));
		List<Response> responses = new ArrayList<>();
		for (int record = 0; record < 12; record++) {
			responses.add(measure("p" + record, COMPLIANT, Phase.PRE_AMENDMENT, "0"));
		}
		for (int record = 0; record < 7; record++) {
			responses.add(measure("q" + record, COMPLIANT, Phase.POST_AMENDMENT, record < 3 ? "1" : "3"));
		}

		List<Histogram> histograms = SingleRecordOutcomeHistograms.build(responses, tests);

		assertThat(histograms).extracting(Histogram::yMax).containsExactly(15, 15);
		assertThat(histograms).extracting(Histogram::maxValue).containsExactly(3, 3);
		assertThat(histograms.get(0).series().get(0).counts()).containsExactly(12, 0, 0, 0);
	}
}
