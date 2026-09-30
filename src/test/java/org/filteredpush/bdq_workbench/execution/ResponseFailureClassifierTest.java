/** ResponseFailureClassifierTest.java
 *
 * Tests classification of responses into completed, transient, ambiguous, configuration, and
 * internal failures.
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

import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ResponseFailureClassifierTest {

	private static final CanonicalRecord RECORD = new CanonicalRecord("r1", Map.of());
	private static final ImplementationBinding BINDING = ExecutionTestSupport.binding("t", TestType.VALIDATION,
			"org.example.Impl", "validate", Phase.PRE_AMENDMENT, "f");

	private static Response external(String comment) {
		return ExecutionTestSupport.externalFailure(RECORD, BINDING, comment);
	}

	@Test
	void ordinaryResultsAreCompleted() {
		assertThat(ResponseFailureClassifier.classify(ExecutionTestSupport.compliant(RECORD, BINDING)))
				.isEqualTo(FailureCategory.COMPLETED);
		assertThat(ResponseFailureClassifier.classify(ExecutionTestSupport.response(RECORD, BINDING, OutcomeStatus.FAILED,
				"INTERNAL_PREREQUISITES_NOT_MET", null, "decimalLatitude is empty")))
				.as("an internal prerequisite is about the data, not the resource")
				.isEqualTo(FailureCategory.COMPLETED);
		assertThat(ResponseFailureClassifier.classify(ExecutionTestSupport.response(RECORD, BINDING, OutcomeStatus.AMENDED,
				"AMENDED", null, "timeout value was amended")))
				.as("structured status wins over text")
				.isEqualTo(FailureCategory.COMPLETED);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"Error accessing Source Authority: java.net.SocketTimeoutException: Read timed out",
			"Connection refused",
			"java.net.UnknownHostException: www.marinespecies.org",
			"Server returned HTTP 429 Too Many Requests",
			"rate limit exceeded",
			"HTTP 503 Service Unavailable",
			"Response code: 502",
			"connect exception while calling service"})
	void transientIndicatorsAreTransient(String comment) {
		assertThat(ResponseFailureClassifier.classify(external(comment))).isEqualTo(FailureCategory.TRANSIENT_EXTERNAL);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"Unsupported Source Authority: Foo",
			"Invalid source authority specified",
			"Source Authority Foo is not supported by this test",
			"HTTP 404 Not Found",
			"status 401",
			"Forbidden",
			"Unknown source authority; timeout would not help"})
	void configurationIndicatorsAreNotTransient(String comment) {
		assertThat(ResponseFailureClassifier.classify(external(comment)))
				.isEqualTo(FailureCategory.NON_TRANSIENT_CONFIGURATION);
	}

	@Test
	void externalFailureWithoutAUsefulDiagnosticIsAmbiguous() {
		assertThat(ResponseFailureClassifier.classify(external(
				"Error with specified Source Authority geospatialLand: Error accessing Source Authority: ")))
				.isEqualTo(FailureCategory.AMBIGUOUS_EXTERNAL);
		assertThat(ResponseFailureClassifier.classify(external(null))).isEqualTo(FailureCategory.AMBIGUOUS_EXTERNAL);
	}

	@Test
	void structuredStatusIsMatchedAcrossNamespacePrefixes() {
		Response prefixed = ExecutionTestSupport.response(RECORD, BINDING, OutcomeStatus.FAILED,
				"bdq:EXTERNAL_PREREQUISITES_NOT_MET", null, "");
		assertThat(ResponseFailureClassifier.classify(prefixed)).isEqualTo(FailureCategory.AMBIGUOUS_EXTERNAL);
		assertThat(ResponseFailureClassifier.isExternalPrerequisiteFailure(prefixed)).isTrue();
	}

	@Test
	void errorsAreInternalUnlessTheyCarryANetworkIndicator() {
		assertThat(ResponseFailureClassifier.classify(ExecutionTestSupport.response(RECORD, BINDING, OutcomeStatus.ERROR,
				"ERROR", null, "NullPointerException: x"))).isEqualTo(FailureCategory.INTERNAL);
		assertThat(ResponseFailureClassifier.classify(ExecutionTestSupport.response(RECORD, BINDING, OutcomeStatus.ERROR,
				"ERROR", null, "SocketTimeoutException: connect timed out"))).isEqualTo(FailureCategory.TRANSIENT_EXTERNAL);
		assertThat(ResponseFailureClassifier.classify(ExecutionTestSupport.response(RECORD, BINDING,
				OutcomeStatus.UNABLE_TO_RUN, "UNABLE_TO_RUN", null, "timeout"))).isEqualTo(FailureCategory.INTERNAL);
		assertThat(ResponseFailureClassifier.classify(null)).isEqualTo(FailureCategory.INTERNAL);
	}
}
