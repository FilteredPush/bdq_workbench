/** InvocationFingerprint.java
 *
 * Identifies everything a test invocation's result can depend on, so that a PRE_AMENDMENT result
 * can safely stand in for an identical POST_AMENDMENT invocation.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.EvaluationSubject;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * The inputs that determine one test invocation's result: the exact implementation (class,
 * method, and the full bound parameter signature), the test's identity and type, its supplied
 * parameter values, the governing relation of the evaluated subject, and the value of every
 * declared {@code ACTED_UPON}/{@code CONSULTED} input as the implementation would read it.
 *
 * <p>The phase is deliberately <em>not</em> part of the fingerprint: two invocations with equal
 * fingerprints, one in PRE_AMENDMENT and one in POST_AMENDMENT, present the implementation with
 * exactly the same arguments.
 *
 * <p>A fingerprint only exists for bindings whose dependencies are completely declared (see
 * {@link #of}); a binding reading the whole record or parameter map, an amendment, or a binding
 * with an unresolved or undeclared input never gets one and is therefore never reused.
 *
 * @param implementationClass the implementation's class
 * @param implementationMethod the implementation's method
 * @param signature the bound parameters' positions, roles, declared terms, and Java types
 * @param testId the test's identifier
 * @param testType the test's type
 * @param parameters the binding's parameter map, sorted by name
 * @param suppliedParameters each {@code PARAMETER}-role argument's source and supplied value, in
 *     position order
 * @param relationName the evaluated subject's governing relation, or {@code null} for the core
 * @param inputs each declared input term and its value on the subject (with
 *     {@link RecordGroupPartitioner#NULL_SENTINEL} for an absent value), sorted by term
 */
record InvocationFingerprint(
		String implementationClass,
		String implementationMethod,
		List<String> signature,
		String testId,
		TestType testType,
		Map<String, String> parameters,
		List<String> suppliedParameters,
		String relationName,
		Map<String, String> inputs) {

	/**
	 * Computes the fingerprint of invoking {@code binding} against {@code subject}, if the binding
	 * declares its dependencies completely enough for the fingerprint to capture them.
	 *
	 * @param binding the binding
	 * @param subject the subject the binding would be invoked against
	 * @return the fingerprint, or empty if {@code binding} is an amendment, has a legacy
	 *     whole-record/whole-parameter argument, has an input without a resolved source term, or
	 *     declares no input terms at all
	 */
	static Optional<InvocationFingerprint> of(ImplementationBinding binding, EvaluationSubject subject) {
		if (binding.testType() == TestType.AMENDMENT) {
			return Optional.empty();
		}
		List<String> signature = new ArrayList<>();
		List<String> supplied = new ArrayList<>();
		Map<String, String> inputs = new TreeMap<>();
		List<BoundMethodParameter> bound = binding.parameterBindings().stream()
				.sorted((a, b) -> Integer.compare(a.parameter().index(), b.parameter().index()))
				.toList();
		for (BoundMethodParameter parameter : bound) {
			ParameterRole role = parameter.parameter().role();
			signature.add(parameter.parameter().index() + ":" + role + ":" + parameter.parameter().source() + ":"
					+ parameter.parameter().typeName());
			if (role == ParameterRole.ACTED_UPON || role == ParameterRole.CONSULTED) {
				String source = parameter.resolvedSource();
				if (source == null) {
					return Optional.empty();
				}
				String value = subject.effectiveRecord().terms().get(source);
				inputs.put(source, value == null ? RecordGroupPartitioner.NULL_SENTINEL : value);
			} else if (role == ParameterRole.PARAMETER) {
				supplied.add(parameter.resolvedSource() + "=" + parameter.suppliedValue());
			} else {
				return Optional.empty();
			}
		}
		if (inputs.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new InvocationFingerprint(
				binding.implementationClass(),
				binding.implementationMethod(),
				List.copyOf(signature),
				binding.testId(),
				binding.testType(),
				new TreeMap<>(binding.parameters()),
				List.copyOf(supplied),
				subject.subjectRef() == null ? null : subject.subjectRef().relationName(),
				inputs));
	}
}
