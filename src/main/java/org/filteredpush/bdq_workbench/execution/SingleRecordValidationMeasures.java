/** SingleRecordValidationMeasures.java
 *
 * Computes the built-in SingleRecord measures that count each record's VALIDATION responses.
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SingleRecordValidationMeasureSpec;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * Synthesizes the responses of {@link SingleRecordValidationMeasureSpec} measures from the
 * VALIDATION responses already produced for each record in a phase.
 *
 * <p>For each record of the phase's dataset, every VALIDATION test with a response for that record
 * is reduced to its record-level response(s) — the derived rollup where the test ran over expanded
 * related rows, otherwise the record's own response — and the distinct tests whose record-level
 * response has the measure's outcome are counted. Following the specification, a record with no
 * VALIDATION responses at all gets INTERNAL_PREREQUISITES_NOT_MET; otherwise the measure reports
 * RUN_HAS_RESULT with the count as its result. If any of the record's VALIDATION tests errored or
 * was unable to run, its outcome is unknown and no count would be accurate, so the measure is
 * reported as {@link OutcomeStatus#ERROR} naming those tests, as the built-in multi-record measures
 * do for failed target responses.
 */
final class SingleRecordValidationMeasures {
	/** Response status for a measure that ran and has a result. */
	private static final String RUN_HAS_RESULT = "RUN_HAS_RESULT";
	/** Response status for a record on which no VALIDATION tests were attempted. */
	private static final String INTERNAL_PREREQUISITES_NOT_MET = "INTERNAL_PREREQUISITES_NOT_MET";
	/** Response status for a record on which a VALIDATION test failed to evaluate. */
	private static final String ERROR = "ERROR";

	/** Not instantiable: a holder for static synthesis logic. */
	private SingleRecordValidationMeasures() {
	}

	/**
	 * Computes one measure's response for every record of the phase's dataset.
	 *
	 * @param phase the phase being executed
	 * @param dataset the phase's dataset, whose records each receive one response
	 * @param measureBinding the measure's binding, as recognized by
	 *     {@link SingleRecordValidationMeasureSpec#isBuiltIn}
	 * @param phaseResponses the responses already produced in this phase, derived rollups included
	 * @return one response per record, in dataset order
	 * @throws IllegalArgumentException if {@code measureBinding} is not such a measure binding
	 */
	static List<Response> synthesize(
			Phase phase,
			RecordDataset dataset,
			ImplementationBinding measureBinding,
			List<Response> phaseResponses) {
		SingleRecordValidationMeasureSpec spec = SingleRecordValidationMeasureSpec.from(measureBinding)
				.orElseThrow(() -> new IllegalArgumentException(
						"Not a built-in single-record validation measure binding: " + measureBinding.testId()));
		Map<String, Map<String, List<Response>>> validationsByRecord = validationsByRecordAndTest(phaseResponses);
		Instant finishedAt = Instant.now();
		List<Response> responses = new ArrayList<>();
		for (CanonicalRecord record : dataset.records()) {
			Map<String, List<Response>> byTest = validationsByRecord.getOrDefault(record.id(), Map.of());
			responses.add(measureResponse(phase, record.id(), measureBinding, spec, byTest, finishedAt));
		}
		return responses;
	}

	/**
	 * Indexes a phase's VALIDATION responses by record ID and then by test ID.
	 *
	 * @param phaseResponses the phase's responses
	 * @return record ID to test ID to that test's responses for the record, in encounter order
	 */
	private static Map<String, Map<String, List<Response>>> validationsByRecordAndTest(List<Response> phaseResponses) {
		Map<String, Map<String, List<Response>>> index = new LinkedHashMap<>();
		for (Response response : phaseResponses) {
			if (response.testType() != TestType.VALIDATION || response.recordId() == null) {
				continue;
			}
			index.computeIfAbsent(response.recordId(), ignored -> new LinkedHashMap<>())
					.computeIfAbsent(response.testId(), ignored -> new ArrayList<>())
					.add(response);
		}
		return index;
	}

	/**
	 * Builds one record's measure response from its VALIDATION responses.
	 *
	 * @param phase the phase being executed
	 * @param recordId the record's ID
	 * @param binding the measure's binding
	 * @param spec the measure's spec
	 * @param byTest the record's VALIDATION responses, keyed by test ID
	 * @param finishedAt the instant to record as both start and finish
	 * @return the record's measure response
	 */
	private static Response measureResponse(
			Phase phase,
			String recordId,
			ImplementationBinding binding,
			SingleRecordValidationMeasureSpec spec,
			Map<String, List<Response>> byTest,
			Instant finishedAt) {
		if (byTest.isEmpty()) {
			String comment = "No VALIDATION Tests were attempted on this record";
			return response(phase, recordId, binding, OutcomeStatus.FAILED, INTERNAL_PREREQUISITES_NOT_MET, null,
					comment, finishedAt);
		}
		List<String> failedTestIds = failedTestIds(byTest);
		if (!failedTestIds.isEmpty()) {
			/*
			 * A validation that errored or could not run has no outcome, so any count would silently
			 * leave it out; report the measure as an error instead, as the multi-record measures do.
			 */
			String comment = "Cannot count VALIDATION Tests with " + spec.tally().criterion() + " because "
					+ failedTestIds.size() + " of " + byTest.size()
					+ " VALIDATION Tests failed or were unable to run on this record: "
					+ String.join(", ", failedTestIds);
			return response(phase, recordId, binding, OutcomeStatus.ERROR, ERROR, null, comment, finishedAt);
		}
		long matching = byTest.values().stream()
				.filter(testResponses -> recordLevel(testResponses).stream().anyMatch(spec::counts))
				.count();
		String comment = matching + " of " + byTest.size() + " VALIDATION Tests had " + spec.tally().criterion();
		return response(phase, recordId, binding, OutcomeStatus.PASSED, RUN_HAS_RESULT, Long.toString(matching),
				comment, finishedAt);
	}

	/**
	 * Lists the tests whose record-level response for a record failed to evaluate.
	 *
	 * @param byTest the record's VALIDATION responses, keyed by test ID
	 * @return the IDs of the tests with a record-level {@link OutcomeStatus#ERROR} or
	 *     {@link OutcomeStatus#UNABLE_TO_RUN} response, in encounter order
	 */
	private static List<String> failedTestIds(Map<String, List<Response>> byTest) {
		return byTest.entrySet().stream()
				.filter(entry -> recordLevel(entry.getValue()).stream()
						.anyMatch(SingleRecordValidationMeasures::failedToEvaluate))
				.map(Map.Entry::getKey)
				.toList();
	}

	/**
	 * Reduces one test's responses for a record to the response(s) that stand for the record: its
	 * derived rollups where present (the test ran once per expanded related row), else all of them.
	 *
	 * @param testResponses one test's responses for one record
	 * @return the record-level responses
	 */
	private static List<Response> recordLevel(List<Response> testResponses) {
		List<Response> rollups = testResponses.stream().filter(Response::derived).toList();
		return rollups.isEmpty() ? testResponses : rollups;
	}

	/**
	 * Reports whether a validation response carries no BDQ outcome because its execution failed.
	 *
	 * @param response the response to inspect
	 * @return {@code true} for {@link OutcomeStatus#ERROR} and {@link OutcomeStatus#UNABLE_TO_RUN}
	 */
	private static boolean failedToEvaluate(Response response) {
		return response.status() == OutcomeStatus.ERROR || response.status() == OutcomeStatus.UNABLE_TO_RUN;
	}

	/**
	 * Builds a measure response for one record.
	 *
	 * @param phase the phase being executed
	 * @param recordId the record's ID
	 * @param binding the measure's binding
	 * @param status the outcome classification
	 * @param responseStatus the BDQ response status
	 * @param responseResult the BDQ response result, or null when there is none
	 * @param comment the response comment, also used as its message
	 * @param finishedAt the instant to record as both start and finish
	 * @return the response
	 */
	private static Response response(
			Phase phase,
			String recordId,
			ImplementationBinding binding,
			OutcomeStatus status,
			String responseStatus,
			String responseResult,
			String comment,
			Instant finishedAt) {
		return new Response(
				recordId,
				binding.testId(),
				binding.testType(),
				binding.implementationClass(),
				binding.implementationMethod(),
				phase,
				binding.parameters(),
				status,
				responseStatus,
				responseResult,
				comment,
				comment,
				Map.of(),
				finishedAt,
				finishedAt);
	}
}
