package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SourceCell;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for amendments reaching post-amendment tests, and for multi-record measures
 * counting records rather than per-row evaluations.
 */
class AmendmentAndMeasureRegressionTest {

	@Test
	void prefixedAmendmentKeyIsWrittenToTheRecordsOwnTermSoPostAmendmentTestsSeeIt() throws Exception {
		CanonicalRecord record = new CanonicalRecord("r1", Map.of("geodeticDatum", "WGS 84"));
		List<String> validated = new CopyOnWriteArrayList<>();
		List<Boolean> prefixedKeyPresent = new CopyOnWriteArrayList<>();
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(1, (subject, binding, implementation) -> {
			if (binding.testType() == TestType.AMENDMENT) {
				return response(subject.id(), binding, OutcomeStatus.AMENDED, "AMENDED", "",
						Map.of("dwc:geodeticDatum", "EPSG:4326"));
			}
			validated.add(subject.terms().get("geodeticDatum"));
			prefixedKeyPresent.add(subject.terms().containsKey("dwc:geodeticDatum"));
			return response(subject.id(), binding, OutcomeStatus.PASSED, "RUN_HAS_RESULT", "COMPLIANT", Map.of());
		}, false);

		service.execute(new RecordDataset(List.of(record)),
				List.of(binding("v1", TestType.VALIDATION, "check", Phase.PRE_AMENDMENT, "dwc:geodeticDatum", "geodeticDatum"),
						binding("a1", TestType.AMENDMENT, "amend", Phase.AMENDMENT, "dwc:geodeticDatum", "geodeticDatum")),
				List.of(discovered("v1", TestType.VALIDATION, Phase.PRE_AMENDMENT, "check", "dwc:geodeticDatum"),
						discovered("a1", TestType.AMENDMENT, Phase.AMENDMENT, "amend", "dwc:geodeticDatum")));

		/* Pre-amendment, then post-amendment. */
		assertThat(validated).containsExactly("WGS 84", "EPSG:4326");
		assertThat(prefixedKeyPresent).containsOnly(false);
	}

	@Test
	void countMeasureCountsRecordsNotPerRowEvaluations() throws Exception {
		CanonicalRecord first = core("occ-1");
		CanonicalRecord second = core("occ-2");
		RecordDataset dataset = new RecordDataset(
				List.of(first, second),
				List.of(
						new RecordGraph(first, Map.of("identification", List.of(
								identification("row-1", "Aus bus"), identification("row-2", "Aus cus"),
								identification("row-3", "Aus dus")))),
						new RecordGraph(second, Map.of("identification", List.of(identification("row-4", "Bus bus"))))));
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(1, (subject, binding, implementation) ->
				response(subject.id(), binding, OutcomeStatus.PASSED, "RUN_HAS_RESULT", "COMPLIANT", Map.of()), false);
		ImplementationBinding measure = new ImplementationBinding(
				"m1", TestType.MEASURE, BuiltInMeasureSpec.IMPLEMENTATION_CLASS, BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
				Phase.PRE_AMENDMENT,
				new BuiltInMeasureSpec(BuiltInMeasureSpec.MeasureKind.COUNT, "SCIENTIFICNAME_FOUND", "v1", "COMPLIANT",
						List.of(), List.of()).asBindingParameters(),
				BindingStatus.BOUND, ParameterizationCapability.DEFAULT_ONLY, "built-in", true, List.of(), List.of());

		List<Response> responses = service.execute(dataset,
				List.of(binding("v1", TestType.VALIDATION, "check", Phase.PRE_AMENDMENT, "dwc:scientificName",
						"scientificName"), measure),
				List.of(discovered("v1", TestType.VALIDATION, Phase.PRE_AMENDMENT, "check", "dwc:scientificName")));

		assertThat(responses).filteredOn(response -> response.testId().equals("m1")
				&& response.phase() == Phase.PRE_AMENDMENT)
				.singleElement()
				.satisfies(response -> {
					assertThat(response.parameters()).containsEntry(BuiltInMeasureSpec.MATCHING_COUNT_KEY, "2")
							.containsEntry(BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "2")
							.containsEntry(BuiltInMeasureSpec.PERCENTAGE_KEY, "100.0");
				});
	}

	private static CanonicalRecord core(String id) {
		return new CanonicalRecord(id, Map.of("occurrenceID", id),
				Map.of("occurrenceID", List.of(new SourceCell("occurrence", "occurrence", id, "occurrenceID", "occurrenceID"))));
	}

	private static CanonicalRecord identification(String rowRef, String name) {
		return new CanonicalRecord(rowRef, Map.of("scientificName", name), Map.of("scientificName",
				List.of(new SourceCell("identification", "identification", rowRef, "scientificName", "scientificName"))));
	}

	private static ImplementationBinding binding(
			String testId, TestType type, String method, Phase phase, String term, String resolvedSource) {
		MethodParameter parameter = new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, term, String.class.getName(), true);
		return new ImplementationBinding(testId, type, Checks.class.getName(), method, phase, Map.of(), BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY, "test", true,
				List.of(new BoundMethodParameter(parameter, resolvedSource, null, true, "Mapped")), List.of());
	}

	private static DiscoveredImplementation discovered(String testId, TestType type, Phase phase, String method,
			String term) throws Exception {
		Method target = Checks.class.getMethod(method, String.class);
		return new DiscoveredImplementation(testId, null, type, phase, Checks.class.getName(), method, null,
				List.of(new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, term, String.class.getName(), true)),
				new Checks(), target);
	}

	private static Response response(String recordId, ImplementationBinding binding, OutcomeStatus status,
			String responseStatus, String result, Map<String, String> amendments) {
		return new Response(recordId, binding.testId(), binding.testType(), binding.implementationClass(),
				binding.implementationMethod(), binding.phase(), binding.parameters(), status, responseStatus, result,
				"", "", amendments, Instant.now(), Instant.now());
	}

	/** Stand-in implementation class; the tests' execution adapters never invoke it. */
	public static class Checks {
		/**
		 * @param value ignored
		 * @return {@code null}
		 */
		public Object check(String value) {
			return null;
		}

		/**
		 * @param value ignored
		 * @return {@code null}
		 */
		public Object amend(String value) {
			return null;
		}
	}
}
