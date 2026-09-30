/** PrePostReuseExecutionTest.java
 *
 * Tests that POST_AMENDMENT reuses successful PRE_AMENDMENT results only for identical,
 * completely declared invocations, and re-invokes everything else.
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
package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.EvaluationSubject;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SubjectRef;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class PrePostReuseExecutionTest {

	private static final String CLASS = "org.example.Tests";

	private final Queue<String> calls = new ConcurrentLinkedQueue<>();

	private final ImplementationBinding validation = ExecutionTestSupport.binding("val", TestType.VALIDATION, CLASS,
			"validationCountrycodeStandard", Phase.PRE_AMENDMENT, "countryCode");
	private final ImplementationBinding amendCountry = ExecutionTestSupport.binding("amendCountry", TestType.AMENDMENT,
			CLASS, "amendmentCountrycodeStandardized", Phase.AMENDMENT, "countryCode");
	private final ImplementationBinding amendLocality = ExecutionTestSupport.binding("amendLocality", TestType.AMENDMENT,
			CLASS, "amendmentLocalityStandardized", Phase.AMENDMENT, "locality");

	/**
	 * Records every invocation as {@code phase:testId:countryCode}; amends v0's country code to
	 * "fixed" and every locality; fails "failing" tests according to their name.
	 */
	private final ExecutionAdapter adapter = (record, binding, implementation) -> {
		String country = record.terms().get("countryCode");
		calls.add(binding.phase() + ":" + binding.testId() + ":" + country);
		switch (binding.testId()) {
			case "amendCountry":
				return "v0".equals(country)
						? ExecutionTestSupport.response(record, binding, OutcomeStatus.AMENDED, "AMENDED", null, "fixed",
								Map.of("dwc:countryCode", "fixed"))
						: ExecutionTestSupport.response(record, binding, OutcomeStatus.PASSED, "NOT_AMENDED", null, "ok");
			case "amendLocality":
				return ExecutionTestSupport.response(record, binding, OutcomeStatus.AMENDED, "AMENDED", null, "tidied",
						Map.of("dwc:locality", record.terms().get("locality") + "!"));
			case "unsupported":
				return ExecutionTestSupport.externalFailure(record, binding, "Unsupported source authority: nowhere");
			case "error":
				return ExecutionTestSupport.response(record, binding, OutcomeStatus.ERROR, "ERROR", null, "boom");
			default:
				return ExecutionTestSupport.compliant(record, binding);
		}
	};

	private static RecordDataset dataset() {
		List<CanonicalRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Map<String, String> terms = new LinkedHashMap<>();
			terms.put("countryCode", "v" + i);
			terms.put("locality", "place " + i);
			records.add(new CanonicalRecord("r" + i, terms));
		}
		return new RecordDataset(records);
	}

	private List<Response> run(ExecutionPolicy policy, ImplementationBinding... bindings) {
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(3, adapter, null, true, policy);
		return service.execute(dataset(), List.of(bindings), List.of());
	}

	private long postCalls(String testId) {
		return calls.stream().filter(call -> call.startsWith("POST_AMENDMENT:" + testId + ":")).count();
	}

	@Test
	void unchangedInputsAreReusedWhileChangedInputsRunAgainAndUnrelatedAmendmentsDoNotForceExecution() {
		List<Response> responses = run(ExecutionPolicy.defaults(), validation, amendCountry, amendLocality);

		assertThat(calls).filteredOn(call -> call.startsWith("POST_AMENDMENT:"))
				.as("only the group whose country code was amended runs again")
				.containsExactly("POST_AMENDMENT:val:fixed");
		List<Response> post = responses.stream()
				.filter(response -> response.phase() == Phase.POST_AMENDMENT && response.testId().equals("val"))
				.toList();
		assertThat(post).extracting(Response::recordId).containsExactly("r0", "r1", "r2");
		assertThat(post).allSatisfy(response -> {
			assertThat(response.phase()).isEqualTo(Phase.POST_AMENDMENT);
			assertThat(response.responseResult()).isEqualTo("COMPLIANT");
		});
		assertThat(post.get(0).message()).doesNotContain(PrePostResultReuse.REUSE_NOTE);
		assertThat(post.subList(1, 3)).allSatisfy(response -> assertThat(response.message())
				.contains(PrePostResultReuse.REUSE_NOTE));
	}

	@Test
	void reuseIsCountedInTheRunStatistics() {
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(3, adapter, null, true,
				ExecutionPolicy.defaults());
		service.execute(dataset(), List.of(validation), List.of());

		assertThat(postCalls("val")).isZero();
		assertThat(service.lastRunStatistics().test("val").orElseThrow().reusedCalls()).isEqualTo(3);
	}

	@Test
	void explicitPostBindingsAreAlwaysInvoked() {
		ImplementationBinding explicitPost = ExecutionTestSupport.binding("val", TestType.VALIDATION, CLASS,
				"validationCountrycodeStandard", Phase.POST_AMENDMENT, "countryCode");

		run(ExecutionPolicy.defaults(), validation, explicitPost);

		assertThat(postCalls("val")).isEqualTo(3);
	}

	@Test
	void failedResultsAreNeverReused() {
		ImplementationBinding unsupported = ExecutionTestSupport.binding("unsupported", TestType.VALIDATION, CLASS,
				"validationTaxonMarine", Phase.PRE_AMENDMENT, "countryCode");
		ImplementationBinding error = ExecutionTestSupport.binding("error", TestType.VALIDATION, CLASS,
				"validationError", Phase.PRE_AMENDMENT, "countryCode");

		run(ExecutionPolicy.defaults(), unsupported, error);

		assertThat(postCalls("unsupported")).isEqualTo(3);
		assertThat(postCalls("error")).isEqualTo(3);
	}

	@Test
	void legacyWholeRecordBindingsAreNeverReused() {
		MethodParameter wholeRecord = new MethodParameter(0, "record", ParameterRole.LEGACY_RECORD, null,
				Map.class.getName(), true);
		ImplementationBinding legacy = new ImplementationBinding("legacy", TestType.VALIDATION, CLASS, "validate",
				Phase.PRE_AMENDMENT, Map.of(), BindingStatus.BOUND, ParameterizationCapability.DEFAULT_ONLY, "test", true,
				List.of(new BoundMethodParameter(wholeRecord, null, null, true, "Whole record")), List.of());

		run(ExecutionPolicy.defaults(), legacy);

		assertThat(postCalls("legacy")).isEqualTo(3);
	}

	@Test
	void reuseCanBeDisabled() {
		run(ExecutionPolicy.builder().prePostReuseEnabled(false).build(), validation);

		assertThat(postCalls("val")).isEqualTo(3);
	}

	@Test
	void fingerprintsCaptureInputsParametersAndTheGoverningRelation() {
		CanonicalRecord record = new CanonicalRecord("r0", Map.of("countryCode", "v0", "locality", "x"));
		CanonicalRecord sameInputs = new CanonicalRecord("r9", Map.of("countryCode", "v0", "locality", "y"));
		CanonicalRecord otherInputs = new CanonicalRecord("r0", Map.of("countryCode", "v1"));
		EvaluationSubject related = new EvaluationSubject("r0", record, record, new RecordGraph(record, Map.of()),
				new SubjectRef("r0", "identification", "identification.txt", "identification.txt", "row-1"));
		ImplementationBinding post = ExecutionTestSupport.binding("val", TestType.VALIDATION, CLASS,
				"validationCountrycodeStandard", Phase.POST_AMENDMENT, "countryCode");
		ImplementationBinding parameterized = ExecutionTestSupport.withParameter(validation, "bdq:sourceAuthority", "A");
		ImplementationBinding otherParameter = ExecutionTestSupport.withParameter(validation, "bdq:sourceAuthority", "B");

		InvocationFingerprint base = InvocationFingerprint.of(validation, EvaluationSubject.flat(record)).orElseThrow();

		assertThat(InvocationFingerprint.of(post, EvaluationSubject.flat(sameInputs))).as("phase and undeclared terms are ignored")
				.contains(base);
		assertThat(InvocationFingerprint.of(validation, EvaluationSubject.flat(otherInputs))).isNotEqualTo(Optional.of(base));
		assertThat(InvocationFingerprint.of(validation, related).orElseThrow()).isNotEqualTo(base);
		assertThat(InvocationFingerprint.of(parameterized, EvaluationSubject.flat(record)))
				.isNotEqualTo(InvocationFingerprint.of(otherParameter, EvaluationSubject.flat(record)));
		assertThat(InvocationFingerprint.of(amendCountry, EvaluationSubject.flat(record))).isEmpty();
	}
}
