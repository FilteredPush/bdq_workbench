/** ExecutionTestSupport.java
 *
 * Shared fixtures for execution scheduling, resilience, and reuse tests: bindings, fake adapter
 * responses, and small datasets, none of which touch the network.
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
import java.util.List;
import java.util.Map;
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
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * Static fixtures shared by the execution tests.
 */
final class ExecutionTestSupport {

	private ExecutionTestSupport() {
	}

	static ImplementationBinding binding(String testId, TestType type, String implementationClass, String method,
			Phase phase, String... fields) {
		List<BoundMethodParameter> bound = new ArrayList<>();
		for (int i = 0; i < fields.length; i++) {
			MethodParameter parameter = new MethodParameter(i, "p" + i, ParameterRole.ACTED_UPON, fields[i],
					String.class.getName(), true);
			bound.add(new BoundMethodParameter(parameter, fields[i], null, true, "Mapped"));
		}
		return new ImplementationBinding(testId, type, implementationClass, method, phase, Map.of(), BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY, "test", true, bound, List.of());
	}

	static ImplementationBinding withParameter(ImplementationBinding binding, String source, String value) {
		List<BoundMethodParameter> bound = new ArrayList<>(binding.parameterBindings());
		MethodParameter parameter = new MethodParameter(bound.size(), "p" + bound.size(), ParameterRole.PARAMETER,
				source, String.class.getName(), false);
		bound.add(new BoundMethodParameter(parameter, source, value, true, "Supplied"));
		Map<String, String> parameters = new java.util.LinkedHashMap<>(binding.parameters());
		if (value != null) {
			parameters.put(source, value);
		}
		return new ImplementationBinding(binding.testId(), binding.testType(), binding.implementationClass(),
				binding.implementationMethod(), binding.phase(), parameters, binding.bindingStatus(),
				ParameterizationCapability.PARAMETERIZED_ONLY, binding.methodSelection(), value == null, bound,
				binding.diagnostics());
	}

	static Response response(CanonicalRecord record, ImplementationBinding binding, OutcomeStatus status,
			String responseStatus, String result, String comment) {
		return response(record, binding, status, responseStatus, result, comment, Map.of());
	}

	static Response response(CanonicalRecord record, ImplementationBinding binding, OutcomeStatus status,
			String responseStatus, String result, String comment, Map<String, String> amendments) {
		Instant now = Instant.now();
		return new Response(record.id(), binding.testId(), binding.testType(), binding.implementationClass(),
				binding.implementationMethod(), binding.phase(), binding.parameters(), status, responseStatus, result,
				comment, comment, amendments, now, now);
	}

	static Response compliant(CanonicalRecord record, ImplementationBinding binding) {
		return response(record, binding, OutcomeStatus.PASSED, "RUN_HAS_RESULT", "COMPLIANT", "ok");
	}

	static Response externalFailure(CanonicalRecord record, ImplementationBinding binding, String comment) {
		return response(record, binding, OutcomeStatus.FAILED, "EXTERNAL_PREREQUISITES_NOT_MET", null, comment);
	}

	static RecordDataset distinctDataset(String field, int count) {
		List<CanonicalRecord> records = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			records.add(new CanonicalRecord("r" + i, new java.util.LinkedHashMap<>(Map.of(field, "v" + i))));
		}
		return new RecordDataset(records);
	}
}
