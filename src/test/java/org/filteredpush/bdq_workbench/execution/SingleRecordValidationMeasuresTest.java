package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionServiceTest.StubDQResponse;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
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
import org.filteredpush.bdq_workbench.model.SingleRecordValidationMeasureSpec;
import org.filteredpush.bdq_workbench.model.SingleRecordValidationMeasureSpec.Tally;
import org.filteredpush.bdq_workbench.model.SourceCell;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.junit.jupiter.api.Test;

/**
 * Tests the built-in SingleRecord measures counting each record's VALIDATION outcomes, run through
 * {@link ParallelPhaseExecutionService}.
 */
class SingleRecordValidationMeasuresTest {
	private static final String EVENT_DATE_TEST = "urn:test:eventDate";
	private static final String COUNTRY_TEST = "urn:test:countryCode";

	/**
	 * Each record gets one response per measure per phase, counting its distinct validations with
	 * the measure's outcome; an errored validation is attempted but counts toward no outcome.
	 */
	@Test
	void countsEachRecordsValidationOutcomesInBothPhases() throws Exception {
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
		List<ImplementationBinding> bindings = List.of(
				validationBinding(EVENT_DATE_TEST, "dwc:eventDate"),
				validationBinding(COUNTRY_TEST, "dwc:countryCode"),
				measureBinding("urn:test:compliant", Tally.COMPLIANT),
				measureBinding("urn:test:notCompliant", Tally.NOT_COMPLIANT),
				measureBinding("urn:test:prerequisites", Tally.PREREQUISITES_NOT_MET));
		RecordDataset dataset = new RecordDataset(List.of(
				new CanonicalRecord("r1", Map.of("dwc:eventDate", "ok", "dwc:countryCode", "ok")),
				new CanonicalRecord("r2", Map.of("dwc:eventDate", "bad", "dwc:countryCode", "")),
				new CanonicalRecord("r3", Map.of("dwc:eventDate", "boom", "dwc:countryCode", "ok"))));

		List<Response> responses = service.execute(dataset, bindings, discovered("dwc:eventDate", "dwc:countryCode"));

		for (Phase phase : List.of(Phase.PRE_AMENDMENT, Phase.POST_AMENDMENT)) {
			assertThat(measureResponses(responses, phase, "urn:test:compliant"))
					.extracting(Response::recordId, Response::responseStatus, Response::responseResult)
					.containsExactly(
							tuple("r1", "RUN_HAS_RESULT", "2"),
							tuple("r2", "RUN_HAS_RESULT", "0"),
							tuple("r3", "RUN_HAS_RESULT", "1"));
			assertThat(measureResponses(responses, phase, "urn:test:notCompliant"))
					.extracting(Response::recordId, Response::responseResult)
					.containsExactly(tuple("r1", "0"), tuple("r2", "1"), tuple("r3", "0"));
			assertThat(measureResponses(responses, phase, "urn:test:prerequisites"))
					.extracting(Response::recordId, Response::responseResult)
					.containsExactly(tuple("r1", "0"), tuple("r2", "1"), tuple("r3", "0"));
		}
		assertThat(measureResponses(responses, Phase.PRE_AMENDMENT, "urn:test:compliant"))
				.allSatisfy(response -> {
					assertThat(response.testType()).isEqualTo(TestType.MEASURE);
					assertThat(response.status()).isEqualTo(OutcomeStatus.PASSED);
				});
		assertThat(measureResponses(responses, Phase.PRE_AMENDMENT, "urn:test:compliant").get(2).comment())
				.isEqualTo("1 of 2 VALIDATION Tests had Response.result=COMPLIANT; 1 could not be evaluated (error or unable to run)");
	}

	/**
	 * A record on which no VALIDATION tests were attempted is INTERNAL_PREREQUISITES_NOT_MET, as
	 * the specification requires.
	 */
	@Test
	void reportsInternalPrerequisitesNotMetWhenNoValidationsWereAttempted() throws Exception {
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(1, new ReflectionExecutionAdapter());
		RecordDataset dataset = new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "ok"))));

		List<Response> responses = service.execute(
				dataset,
				List.of(measureBinding("urn:test:compliant", Tally.COMPLIANT)),
				List.of());

		assertThat(responses)
				.extracting(Response::phase, Response::recordId, Response::status, Response::responseStatus,
						Response::responseResult)
				.containsExactly(
						tuple(Phase.PRE_AMENDMENT, "r1", OutcomeStatus.FAILED, "INTERNAL_PREREQUISITES_NOT_MET", null),
						tuple(Phase.POST_AMENDMENT, "r1", OutcomeStatus.FAILED, "INTERNAL_PREREQUISITES_NOT_MET", null));
	}

	/**
	 * A validation run once per expanded related row counts once for its record, through the
	 * derived rollup, not once per row.
	 */
	@Test
	void countsAnExpandedValidationOnceThroughItsRollup() throws Exception {
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
		CanonicalRecord core = provenancedRecord("occ-1", "occurrence", Map.of("occurrenceID", "occ-1"));
		RecordDataset dataset = new RecordDataset(
				List.of(core),
				List.of(new RecordGraph(core, Map.of("identification", List.of(
						provenancedRecord("id-1", "identification", Map.of("scientificName", "ok")),
						provenancedRecord("id-2", "identification", Map.of("scientificName", "ok")))))));

		List<Response> responses = service.execute(
				dataset,
				List.of(
						validationBinding(EVENT_DATE_TEST, "scientificName"),
						measureBinding("urn:test:compliant", Tally.COMPLIANT)),
				discovered("scientificName"));

		assertThat(measureResponses(responses, Phase.PRE_AMENDMENT, "urn:test:compliant"))
				.extracting(Response::recordId, Response::responseResult)
				.containsExactly(tuple("occ-1", "1"));
	}

	/**
	 * Recognizes the ratified measures by any version of their test IRI or by label, and only as
	 * MEASUREs.
	 */
	@Test
	void recognizesRatifiedMeasuresByIdOrLabel() {
		assertThat(SingleRecordValidationMeasureSpec.from(new org.filteredpush.bdq_workbench.model.TestDefinition(
				"https://rs.tdwg.org/bdqtest/terms/453844ae-9df4-439f-8e24-c52498eca84a-2024-09-24",
				null, TestType.MEASURE, Phase.PRE_AMENDMENT, Map.of())))
				.map(SingleRecordValidationMeasureSpec::tally)
				.contains(Tally.NOT_COMPLIANT);
		assertThat(SingleRecordValidationMeasureSpec.from(new org.filteredpush.bdq_workbench.model.TestDefinition(
				"urn:local:measure", "MEASURE_VALIDATIONTESTS_PREREQUISITESNOTMET",
				TestType.MEASURE, Phase.PRE_AMENDMENT, Map.of())))
				.map(SingleRecordValidationMeasureSpec::tally)
				.contains(Tally.PREREQUISITES_NOT_MET);
		assertThat(SingleRecordValidationMeasureSpec.from(new org.filteredpush.bdq_workbench.model.TestDefinition(
				"https://rs.tdwg.org/bdqtest/terms/45fb49eb-4a1b-4b49-876f-15d5034dfc73-2024-09-25",
				"MEASURE_VALIDATIONTESTS_COMPLIANT", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of())))
				.isEmpty();
		ImplementationBinding binding = measureBinding("urn:test:compliant", Tally.COMPLIANT);
		assertThat(SingleRecordValidationMeasureSpec.from(binding))
				.map(SingleRecordValidationMeasureSpec::tally)
				.contains(Tally.COMPLIANT);
	}

	private static List<Response> measureResponses(List<Response> responses, Phase phase, String testId) {
		return responses.stream()
				.filter(response -> response.phase() == phase && response.testId().equals(testId))
				.toList();
	}

	private static List<DiscoveredImplementation> discovered(String... fields) throws NoSuchMethodException {
		Method validate = OutcomeImpl.class.getMethod("validate", String.class);
		List<String> testIds = List.of(EVENT_DATE_TEST, COUNTRY_TEST);
		List<DiscoveredImplementation> discovered = new java.util.ArrayList<>();
		for (int index = 0; index < fields.length; index++) {
			discovered.add(new DiscoveredImplementation(testIds.get(index), null, TestType.VALIDATION, Phase.PRE_AMENDMENT,
					OutcomeImpl.class.getName(), "validate", null,
					List.of(parameter(fields[index])), new OutcomeImpl(), validate));
		}
		return discovered;
	}

	private static MethodParameter parameter(String field) {
		return new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, field, String.class.getName(), true);
	}

	private static ImplementationBinding validationBinding(String testId, String field) {
		return new ImplementationBinding(
				testId,
				TestType.VALIDATION,
				OutcomeImpl.class.getName(),
				"validate",
				Phase.PRE_AMENDMENT,
				Map.of(),
				BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY,
				"test",
				true,
				List.of(new BoundMethodParameter(parameter(field), field, null, true, "Mapped")),
				List.of());
	}

	private static ImplementationBinding measureBinding(String testId, Tally tally) {
		return new ImplementationBinding(
				testId,
				TestType.MEASURE,
				SingleRecordValidationMeasureSpec.IMPLEMENTATION_CLASS,
				SingleRecordValidationMeasureSpec.IMPLEMENTATION_METHOD,
				Phase.PRE_AMENDMENT,
				new SingleRecordValidationMeasureSpec(tally).asBindingParameters(tally.testLabel()),
				BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY,
				"built-in single-record validation measure",
				true,
				List.of(),
				List.of());
	}

	private static CanonicalRecord provenancedRecord(String id, String table, Map<String, String> terms) {
		Map<String, List<SourceCell>> provenance = new LinkedHashMap<>();
		terms.keySet().forEach(term -> provenance.put(term, List.of(new SourceCell(table, table, id, term, term))));
		return new CanonicalRecord(id, terms, provenance);
	}

	/** A validation whose outcome is named by the value it is given. */
	public static class OutcomeImpl {
		/**
		 * @param value "ok", "bad", empty, or "boom"
		 * @return COMPLIANT, NOT_COMPLIANT, or INTERNAL_PREREQUISITES_NOT_MET respectively
		 * @throws IllegalStateException for "boom"
		 */
		public StubDQResponse validate(String value) {
			if ("boom".equals(value)) {
				throw new IllegalStateException("boom");
			}
			if (value == null || value.isEmpty()) {
				return new StubDQResponse("INTERNAL_PREREQUISITES_NOT_MET", null, "empty", Map.of());
			}
			return new StubDQResponse("RUN_HAS_RESULT", "ok".equals(value) ? "COMPLIANT" : "NOT_COMPLIANT", value, Map.of());
		}
	}
}
