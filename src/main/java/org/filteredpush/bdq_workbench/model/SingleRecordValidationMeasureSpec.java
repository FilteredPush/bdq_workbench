/** SingleRecordValidationMeasureSpec.java
 *
 * Identifies and represents the built-in SingleRecord MEASURE tests that count, for each record,
 * how many VALIDATION tests produced a given response.
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
package org.filteredpush.bdq_workbench.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A built-in SingleRecord measure over the VALIDATION responses of one record.
 *
 * <p>The ratified BDQ tests include three measures whose information element is
 * {@code bdqval:AllValidationTestsRunOnSingleRecord}: MEASURE_VALIDATIONTESTS_COMPLIANT,
 * MEASURE_VALIDATIONTESTS_NOTCOMPLIANT and MEASURE_VALIDATIONTESTS_PREREQUISITESNOTMET. Each
 * reports, for a single record, the number of distinct VALIDATION tests whose response matched
 * one outcome, and is INTERNAL_PREREQUISITES_NOT_MET when no VALIDATION tests were attempted on
 * the record. No implementation library can provide them, since their input is the other tests'
 * responses rather than record terms, so the workbench computes them itself once a phase's
 * validations have run.
 *
 * <p>A {@link TestDefinition} is recognized by the test's UUID (so any version of the test
 * matches) or, failing that, by its label (see {@link #from(TestDefinition)}). A bound measure
 * carries its {@link Tally} as a binding parameter, from which {@link #from(ImplementationBinding)}
 * recovers the spec at execution time.
 *
 * @param tally which validation outcome this measure counts
 */
public record SingleRecordValidationMeasureSpec(Tally tally) {
	/** Fully-qualified class name used as the synthetic implementation class for these measures. */
	public static final String IMPLEMENTATION_CLASS = SingleRecordValidationMeasureSpec.class.getName();
	/** Synthetic implementation method name recorded on bindings for these measures. */
	public static final String IMPLEMENTATION_METHOD = "countValidationResponses";
	/** Binding parameter key holding the {@link Tally} name. */
	public static final String TALLY_KEY = "_builtin.singleRecordMeasure.tally";
	/** Binding parameter key holding the measure's own label. */
	public static final String MEASURE_LABEL_KEY = "_builtin.singleRecordMeasure.label";

	/** Response status of a validation that could not run because an internal prerequisite was not met. */
	private static final String INTERNAL_PREREQUISITES_NOT_MET = "INTERNAL_PREREQUISITES_NOT_MET";
	/** Response status of a validation that could not run because an external prerequisite was not met. */
	private static final String EXTERNAL_PREREQUISITES_NOT_MET = "EXTERNAL_PREREQUISITES_NOT_MET";

	/**
	 * Canonical constructor.
	 *
	 * @throws IllegalArgumentException if {@code tally} is null
	 */
	public SingleRecordValidationMeasureSpec {
		if (tally == null) {
			throw new IllegalArgumentException("tally is required");
		}
	}

	/**
	 * Recognizes a test definition as one of the built-in single-record validation measures.
	 *
	 * @param test the test definition to inspect, possibly null
	 * @return the spec, or {@link Optional#empty()} if {@code test} is null, not a MEASURE, or
	 *     neither its ID nor its label names one of the {@link Tally} measures
	 */
	public static Optional<SingleRecordValidationMeasureSpec> from(TestDefinition test) {
		if (test == null || test.type() != TestType.MEASURE) {
			return Optional.empty();
		}
		String id = test.id() == null ? "" : test.id().toLowerCase(Locale.ROOT);
		String label = test.label() == null ? "" : test.label().trim();
		for (Tally tally : Tally.values()) {
			if (id.contains(tally.testUuid()) || tally.testLabel().equals(label)) {
				return Optional.of(new SingleRecordValidationMeasureSpec(tally));
			}
		}
		return Optional.empty();
	}

	/**
	 * Recovers the spec recorded on a binding by {@link #asBindingParameters()}.
	 *
	 * @param binding the binding to inspect, possibly null
	 * @return the spec, or {@link Optional#empty()} if {@code binding} is not a built-in
	 *     single-record validation measure binding
	 */
	public static Optional<SingleRecordValidationMeasureSpec> from(ImplementationBinding binding) {
		if (!isBuiltIn(binding)) {
			return Optional.empty();
		}
		try {
			return Optional.of(new SingleRecordValidationMeasureSpec(
					Tally.valueOf(binding.parameters().get(TALLY_KEY))));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/**
	 * Determines whether a binding represents one of these built-in measures, as opposed to a
	 * discovered, reflection-invoked implementation.
	 *
	 * @param binding the binding to inspect, possibly null
	 * @return {@code true} if {@code binding} targets this type's synthetic implementation and
	 *     carries a tally
	 */
	public static boolean isBuiltIn(ImplementationBinding binding) {
		return binding != null
				&& binding.testType() == TestType.MEASURE
				&& IMPLEMENTATION_CLASS.equals(binding.implementationClass())
				&& IMPLEMENTATION_METHOD.equals(binding.implementationMethod())
				&& binding.parameters().containsKey(TALLY_KEY);
	}

	/**
	 * Renders this spec as the parameters recorded on its synthetic {@link ImplementationBinding}.
	 *
	 * @param measureLabel the measure test's own label, recorded for reports; may be null
	 * @return an immutable map of binding parameter keys to values
	 */
	public Map<String, String> asBindingParameters(String measureLabel) {
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put(TALLY_KEY, tally.name());
		parameters.put(MEASURE_LABEL_KEY, measureLabel == null || measureLabel.isBlank() ? tally.testLabel() : measureLabel);
		return Map.copyOf(parameters);
	}

	/**
	 * Describes what this measure computes, for binding diagnostics.
	 *
	 * @return a one-line description
	 */
	public String description() {
		return "Built-in SingleRecord measure counting, for each record, the distinct VALIDATION tests with "
				+ tally.criterion();
	}

	/**
	 * Checks whether one validation response counts toward this measure.
	 *
	 * @param response a VALIDATION response for the record, possibly null
	 * @return {@code true} if the response has the outcome this measure counts
	 */
	public boolean counts(Response response) {
		if (response == null) {
			return false;
		}
		return switch (tally) {
			case COMPLIANT -> "COMPLIANT".equals(response.responseResult());
			case NOT_COMPLIANT -> "NOT_COMPLIANT".equals(response.responseResult());
			case PREREQUISITES_NOT_MET -> INTERNAL_PREREQUISITES_NOT_MET.equals(response.responseStatus())
					|| EXTERNAL_PREREQUISITES_NOT_MET.equals(response.responseStatus());
		};
	}

	/**
	 * The validation outcome each built-in single-record measure counts, with the identity of the
	 * ratified test it implements.
	 */
	public enum Tally {
		/** MEASURE_VALIDATIONTESTS_COMPLIANT: validations with {@code Response.result=COMPLIANT}. */
		COMPLIANT(
				"45fb49eb-4a1b-4b49-876f-15d5034dfc73",
				"MEASURE_VALIDATIONTESTS_COMPLIANT",
				"Response.result=COMPLIANT"),
		/** MEASURE_VALIDATIONTESTS_NOTCOMPLIANT: validations with {@code Response.result=NOT_COMPLIANT}. */
		NOT_COMPLIANT(
				"453844ae-9df4-439f-8e24-c52498eca84a",
				"MEASURE_VALIDATIONTESTS_NOTCOMPLIANT",
				"Response.result=NOT_COMPLIANT"),
		/**
		 * MEASURE_VALIDATIONTESTS_PREREQUISITESNOTMET: validations with
		 * {@code Response.status} INTERNAL_PREREQUISITES_NOT_MET or EXTERNAL_PREREQUISITES_NOT_MET.
		 */
		PREREQUISITES_NOT_MET(
				"49a94636-a562-4e6b-803c-665c80628a3d",
				"MEASURE_VALIDATIONTESTS_PREREQUISITESNOTMET",
				"Response.status=INTERNAL_PREREQUISITES_NOT_MET or EXTERNAL_PREREQUISITES_NOT_MET");

		private final String testUuid;
		private final String testLabel;
		private final String criterion;

		/**
		 * @param testUuid the version-independent UUID of the ratified test
		 * @param testLabel the ratified test's label
		 * @param criterion the counted outcome, as the specification states it
		 */
		Tally(String testUuid, String testLabel, String criterion) {
			this.testUuid = testUuid;
			this.testLabel = testLabel;
			this.criterion = criterion;
		}

		/**
		 * @return the version-independent UUID of the ratified test, lower case
		 */
		public String testUuid() {
			return testUuid;
		}

		/**
		 * @return the ratified test's label
		 */
		public String testLabel() {
			return testLabel;
		}

		/**
		 * @return the counted outcome, as the specification states it
		 */
		public String criterion() {
			return criterion;
		}
	}
}
