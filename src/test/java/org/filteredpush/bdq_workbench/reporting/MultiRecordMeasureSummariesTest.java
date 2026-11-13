package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;
import org.filteredpush.bdq_workbench.reporting.MultiRecordMeasureSummaries.Row;
import org.filteredpush.bdq_workbench.reporting.MultiRecordMeasureSummaries.Summary;
import org.junit.jupiter.api.Test;

/** Tests the summary of the multi-record COUNT and QA measures for the overview charts. */
class MultiRecordMeasureSummariesTest {

	/**
	 * Orders COUNT measures by improvement, greatest first, then unchanged, then worse, then those
	 * without both phases; pairs each with the QA measure on the same target; counts QA results.
	 */
	@Test
	void ordersByImprovementAndPairsQaResults() {
		List<Response> responses = new ArrayList<>();
		count(responses, "c-small", "v-small", "SMALL", 80, 85);
		count(responses, "c-big", "v-big", "BIG", 10, 90);
		count(responses, "c-same", "v-same", "SAME", 50, 50);
		count(responses, "c-worse", "v-worse", "WORSE", 70, 60);
		responses.add(countResponse("c-pre", "v-pre", "PRE_ONLY", Phase.PRE_AMENDMENT, 5));
		qa(responses, "q-big", "v-big", "NOT_COMPLETE", "COMPLETE");
		qa(responses, "q-same", "v-same", "NOT_COMPLETE", "NOT_COMPLETE");
		List<TestDefinition> tests = List.of(new TestDefinition("c-big", "c-big", TestType.MEASURE, Phase.PRE_AMENDMENT,
				Map.of(), Map.of(RdfPolicyResolverService.TEST_METADATA_DIMENSION, "Conformance")));

		Summary summary = MultiRecordMeasureSummaries.build(responses, tests);

		assertThat(summary.rows()).extracting(Row::targetLabel)
				.containsExactly("BIG", "SMALL", "SAME", "WORSE", "PRE_ONLY");
		Row big = summary.rows().get(0);
		assertThat(big.dimension()).isEqualTo("Conformance");
		assertThat(big.change()).isEqualTo(80d);
		assertThat(big.qaPre()).isEqualTo("NOT_COMPLETE");
		assertThat(big.qaPost()).isEqualTo("COMPLETE");
		assertThat(summary.rows().get(1).qaPre()).isNull();
		assertThat(summary.improved()).isEqualTo(2);
		assertThat(summary.unchanged()).isEqualTo(1);
		assertThat(summary.worse()).isEqualTo(1);
		assertThat(summary.median(Phase.PRE_AMENDMENT)).isEqualTo(50d);   // 5, 10, 50, 70, 80
		assertThat(summary.median(Phase.POST_AMENDMENT)).isEqualTo(72.5d); // 50, 60, 85, 90
		assertThat(summary.qaMeasures()).isEqualTo(2);
		assertThat(summary.qaCompletePre()).isZero();
		assertThat(summary.qaWithPre()).isEqualTo(2);
		assertThat(summary.qaCompletePost()).isEqualTo(1);
		assertThat(summary.qaWithPost()).isEqualTo(2);
	}

	/** Without multi-record measure responses, the summary is empty. */
	@Test
	void emptyWithoutMultiRecordMeasures() {
		assertThat(MultiRecordMeasureSummaries.build(List.of(), List.of()).isEmpty()).isTrue();
	}

	private static void count(List<Response> responses, String testId, String target, String targetLabel,
			int pre, int post) {
		responses.add(countResponse(testId, target, targetLabel, Phase.PRE_AMENDMENT, pre));
		responses.add(countResponse(testId, target, targetLabel, Phase.POST_AMENDMENT, post));
	}

	private static Response countResponse(String testId, String target, String targetLabel, Phase phase, int count) {
		Map<String, String> parameters = Map.of(
				BuiltInMeasureSpec.KIND_KEY, "COUNT",
				BuiltInMeasureSpec.TARGET_TEST_ID_KEY, target,
				BuiltInMeasureSpec.TARGET_LABEL_KEY, targetLabel,
				BuiltInMeasureSpec.MEASURE_LABEL_KEY, "MULTIRECORD_MEASURE_COUNT_COMPLIANT_" + targetLabel,
				BuiltInMeasureSpec.MATCHING_COUNT_KEY, Integer.toString(count),
				BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "100");
		return new Response("MULTIRECORD", testId, TestType.MEASURE, "impl", "method", phase, parameters,
				OutcomeStatus.PASSED, "RUN_HAS_RESULT", Integer.toString(count), "", "", Map.of(), Instant.EPOCH, Instant.EPOCH);
	}

	private static void qa(List<Response> responses, String testId, String target, String pre, String post) {
		for (Phase phase : List.of(Phase.PRE_AMENDMENT, Phase.POST_AMENDMENT)) {
			Map<String, String> parameters = Map.of(BuiltInMeasureSpec.KIND_KEY, "QA",
					BuiltInMeasureSpec.TARGET_TEST_ID_KEY, target);
			responses.add(new Response("MULTIRECORD", testId, TestType.MEASURE, "impl", "method", phase, parameters,
					OutcomeStatus.PASSED, "RUN_HAS_RESULT", phase == Phase.PRE_AMENDMENT ? pre : post, "", "", Map.of(),
					Instant.EPOCH, Instant.EPOCH));
		}
	}
}
