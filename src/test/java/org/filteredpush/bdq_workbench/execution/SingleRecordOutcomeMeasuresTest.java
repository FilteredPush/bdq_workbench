package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.lang.reflect.Method;
import java.time.Instant;
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
import org.filteredpush.bdq_workbench.model.SingleRecordOutcomeMeasureSpec;
import org.filteredpush.bdq_workbench.model.SingleRecordOutcomeMeasureSpec.Tally;
import org.filteredpush.bdq_workbench.model.SourceCell;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.junit.jupiter.api.Test;

/**
 * Tests the built-in SingleRecord measures counting each record's VALIDATION or AMENDMENT outcomes,
 * run through {@link ParallelPhaseExecutionService}.
 */
class SingleRecordOutcomeMeasuresTest {
	private static final String EVENT_DATE_TEST = "urn:test:eventDate";
	private static final String COUNTRY_TEST = "urn:test:countryCode";

	/**
	 * Each record gets one response per measure per phase, counting its distinct validations with
	 * the measure's outcome; a record with an errored validation gets an ERROR measure instead.
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
							tuple("r3", "ERROR", null));
			assertThat(measureResponses(responses, phase, "urn:test:notCompliant"))
					.extracting(Response::recordId, Response::responseResult)
					.containsExactly(tuple("r1", "0"), tuple("r2", "1"), tuple("r3", null));
			assertThat(measureResponses(responses, phase, "urn:test:prerequisites"))
					.extracting(Response::recordId, Response::responseResult)
					.containsExactly(tuple("r1", "0"), tuple("r2", "1"), tuple("r3", null));
		}
		List<Response> compliant = measureResponses(responses, Phase.PRE_AMENDMENT, "urn:test:compliant");
		assertThat(compliant).allSatisfy(response -> assertThat(response.testType()).isEqualTo(TestType.MEASURE));
		assertThat(compliant).extracting(Response::status)
				.containsExactly(OutcomeStatus.PASSED, OutcomeStatus.PASSED, OutcomeStatus.ERROR);
		assertThat(compliant.get(1).comment()).isEqualTo("0 of 2 VALIDATION Tests had Response.result=COMPLIANT");
		assertThat(compliant.get(2).comment()).isEqualTo(
				"Cannot count VALIDATION Tests with Response.result=COMPLIANT because 1 of 2"
						+ " VALIDATION Tests failed or were unable to run on this record: " + EVENT_DATE_TEST);
	}

	/**
	 * A record on which no VALIDATION tests were run gets RUN_HAS_RESULT with result 0, as the
	 * specification says.
	 */
	@Test
	void reportsZeroWhenNoValidationsWereRun() throws Exception {
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(1, new ReflectionExecutionAdapter());
		RecordDataset dataset = new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "ok"))));

		List<Response> responses = service.execute(
				dataset,
				List.of(measureBinding("urn:test:compliant", Tally.COMPLIANT)),
				List.of());

		assertThat(responses)
				.extracting(Response::phase, Response::recordId, Response::status, Response::responseStatus,
						Response::responseResult, Response::comment)
				.containsExactly(
						tuple(Phase.PRE_AMENDMENT, "r1", OutcomeStatus.PASSED, "RUN_HAS_RESULT", "0",
								"No VALIDATION Tests were run on this record"),
						tuple(Phase.POST_AMENDMENT, "r1", OutcomeStatus.PASSED, "RUN_HAS_RESULT", "0",
								"No VALIDATION Tests were run on this record"));
	}

	/**
	 * MEASURE_AMENDMENTS_PROPOSED is computed in the amendment phase only, counting each record's
	 * distinct AMENDMENT tests with Response.status AMENDED or FILLED_IN; 0 when none proposed a
	 * change or none were run, and ERROR when one of the record's amendments errored.
	 */
	@Test
	void countsProposedAmendmentsInTheAmendmentPhase() throws Exception {
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(1, (subject, binding, implementation) -> {
			String value = subject.terms().getOrDefault(binding.testId(), "");
			return switch (value) {
				case "amend" -> response(subject.id(), binding, OutcomeStatus.AMENDED, "AMENDED",
						Map.of("dwc:x", "new"));
				case "fill" -> response(subject.id(), binding, OutcomeStatus.AMENDED, "FILLED_IN",
						Map.of("dwc:x", "new"));
				case "boom" -> response(subject.id(), binding, OutcomeStatus.ERROR, "ERROR", Map.of());
				default -> response(subject.id(), binding, OutcomeStatus.PASSED, "NOT_AMENDED", Map.of());
			};
		}, false);
		RecordDataset dataset = new RecordDataset(List.of(
				new CanonicalRecord("r1", Map.of("a1", "amend", "a2", "fill")),
				new CanonicalRecord("r2", Map.of("a1", "keep", "a2", "keep")),
				new CanonicalRecord("r3", Map.of("a1", "amend", "a2", "boom"))));

		List<Response> responses = service.execute(
				dataset,
				List.of(
						amendmentBinding("a1"),
						amendmentBinding("a2"),
						measureBinding("urn:test:amendmentsProposed", Tally.AMENDMENTS_PROPOSED)),
				List.of(discoveredAmendment("a1"), discoveredAmendment("a2")));

		assertThat(responses).filteredOn(response -> response.testId().equals("urn:test:amendmentsProposed"))
				.extracting(Response::phase, Response::recordId, Response::responseStatus, Response::responseResult)
				.containsExactly(
						tuple(Phase.AMENDMENT, "r1", "RUN_HAS_RESULT", "2"),
						tuple(Phase.AMENDMENT, "r2", "RUN_HAS_RESULT", "0"),
						tuple(Phase.AMENDMENT, "r3", "ERROR", null));
		assertThat(measureResponses(responses, Phase.AMENDMENT, "urn:test:amendmentsProposed").get(0).comment())
				.isEqualTo("2 of 2 AMENDMENT Tests had Response.status=AMENDED or FILLED_IN");
	}

	/** MEASURE_AMENDMENTS_PROPOSED on a record with no AMENDMENT tests run is 0. */
	@Test
	void reportsZeroWhenNoAmendmentsWereRun() throws Exception {
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(1, new ReflectionExecutionAdapter());
		RecordDataset dataset = new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "ok"))));

		List<Response> responses = service.execute(
				dataset,
				List.of(measureBinding("urn:test:amendmentsProposed", Tally.AMENDMENTS_PROPOSED)),
				List.of());

		assertThat(responses)
				.extracting(Response::phase, Response::responseStatus, Response::responseResult, Response::comment)
				.containsExactly(tuple(Phase.AMENDMENT, "RUN_HAS_RESULT", "0", "No AMENDMENT Tests were run on this record"));
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
		assertThat(SingleRecordOutcomeMeasureSpec.from(new org.filteredpush.bdq_workbench.model.TestDefinition(
				"https://rs.tdwg.org/bdqtest/terms/453844ae-9df4-439f-8e24-c52498eca84a-2024-09-24",
				null, TestType.MEASURE, Phase.PRE_AMENDMENT, Map.of())))
				.map(SingleRecordOutcomeMeasureSpec::tally)
				.contains(Tally.NOT_COMPLIANT);
		assertThat(SingleRecordOutcomeMeasureSpec.from(new org.filteredpush.bdq_workbench.model.TestDefinition(
				"urn:local:measure", "MEASURE_VALIDATIONTESTS_PREREQUISITESNOTMET",
				TestType.MEASURE, Phase.PRE_AMENDMENT, Map.of())))
				.map(SingleRecordOutcomeMeasureSpec::tally)
				.contains(Tally.PREREQUISITES_NOT_MET);
		assertThat(SingleRecordOutcomeMeasureSpec.from(new org.filteredpush.bdq_workbench.model.TestDefinition(
				"https://rs.tdwg.org/bdqtest/terms/45fb49eb-4a1b-4b49-876f-15d5034dfc73-2024-09-25",
				"MEASURE_VALIDATIONTESTS_COMPLIANT", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of())))
				.isEmpty();
		assertThat(SingleRecordOutcomeMeasureSpec.from(new org.filteredpush.bdq_workbench.model.TestDefinition(
				"https://rs.tdwg.org/bdqtest/terms/03049fe5-a575-404f-b564-ae63f5a1cf8b-2026-11-12",
				null, TestType.MEASURE, Phase.PRE_AMENDMENT, Map.of())))
				.map(SingleRecordOutcomeMeasureSpec::tally)
				.contains(Tally.AMENDMENTS_PROPOSED);
		ImplementationBinding binding = measureBinding("urn:test:compliant", Tally.COMPLIANT);
		assertThat(SingleRecordOutcomeMeasureSpec.from(binding))
				.map(SingleRecordOutcomeMeasureSpec::tally)
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
				SingleRecordOutcomeMeasureSpec.IMPLEMENTATION_CLASS,
				SingleRecordOutcomeMeasureSpec.IMPLEMENTATION_METHOD,
				tally.phase(Phase.PRE_AMENDMENT),
				new SingleRecordOutcomeMeasureSpec(tally).asBindingParameters(tally.testLabel()),
				BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY,
				"built-in single-record outcome measure",
				true,
				List.of(),
				List.of());
	}

	private static ImplementationBinding amendmentBinding(String testId) {
		MethodParameter parameter = new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, testId, String.class.getName(), true);
		return new ImplementationBinding(testId, TestType.AMENDMENT, AmendImpl.class.getName(), "amend", Phase.AMENDMENT,
				Map.of(), BindingStatus.BOUND, ParameterizationCapability.DEFAULT_ONLY, "test", true,
				List.of(new BoundMethodParameter(parameter, testId, null, true, "Mapped")), List.of());
	}

	private static DiscoveredImplementation discoveredAmendment(String testId) throws NoSuchMethodException {
		return new DiscoveredImplementation(testId, null, TestType.AMENDMENT, Phase.AMENDMENT, AmendImpl.class.getName(),
				"amend", null,
				List.of(new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, testId, String.class.getName(), true)),
				new AmendImpl(), AmendImpl.class.getMethod("amend", String.class));
	}

	private static Response response(String recordId, ImplementationBinding binding, OutcomeStatus status,
			String responseStatus, Map<String, String> amendments) {
		return new Response(recordId, binding.testId(), binding.testType(), binding.implementationClass(),
				binding.implementationMethod(), binding.phase(), binding.parameters(), status, responseStatus, null,
				"", "", amendments, Instant.now(), Instant.now());
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

	/** Stand-in amendment class; the amendment tests' execution adapter never invokes it. */
	public static class AmendImpl {
		/**
		 * @param value ignored
		 * @return {@code null}
		 */
		public Object amend(String value) {
			return null;
		}
	}
}
