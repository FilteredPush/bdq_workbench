/** BuiltInMeasureSpec.java
 *
 * Parses and represents the metadata needed to synthesize built-in MEASURE test results (COUNT
 * and QA measures) that summarize other tests' responses across a multi-record dataset.
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed metadata for built-in multi-record measures synthesized from response streams.
 *
 * <p>A {@code BuiltInMeasureSpec} is derived from a {@link TestDefinition} whose label follows
 * the {@code MULTIRECORD_MEASURE_COUNT_*} or {@code MULTIRECORD_MEASURE_QA_*} naming convention
 * (see {@link #from(TestDefinition)}), or recovered from an already-bound
 * {@link ImplementationBinding} (see {@link #from(ImplementationBinding)}) via the parameter keys
 * declared as constants on this type. A {@code COUNT} spec tallies how many responses for a
 * target test matched a specific {@link Response#responseResult()}; a {@code QA} spec instead
 * checks each response against a set of acceptable results/statuses (see
 * {@link #matchesQaCondition(Response)}).
 *
 * @param kind whether this spec describes a COUNT or QA measure
 * @param targetTestLabel the label of the test whose responses this measure summarizes
 * @param targetTestId the ID of the test whose responses this measure summarizes, if known
 * @param responseResult for a COUNT measure, the {@link Response#responseResult()} value being
 *     tallied
 * @param acceptableResponseResults for a QA measure, the {@link Response#responseResult()} values
 *     that count as passing
 * @param acceptableResponseStatuses for a QA measure, the {@link Response#responseStatus()}
 *     values that count as passing
 */
public record BuiltInMeasureSpec(
        MeasureKind kind,
        String targetTestLabel,
        String targetTestId,
        String responseResult,
        List<String> acceptableResponseResults,
        List<String> acceptableResponseStatuses) {
    /** Fully-qualified class name used as the synthetic implementation class for built-in measures. */
    public static final String IMPLEMENTATION_CLASS = BuiltInMeasureSpec.class.getName();
    /** Synthetic implementation method name recorded on bindings for built-in measures. */
    public static final String IMPLEMENTATION_METHOD = "evaluateBuiltInMeasure";
    /** Binding parameter key holding the {@link MeasureKind} name. */
    public static final String KIND_KEY = "_builtin.measure.kind";
    /** Binding parameter key holding the measure's own label. */
    public static final String MEASURE_LABEL_KEY = "_builtin.measure.label";
    /** Binding parameter key holding the target test's label. */
    public static final String TARGET_LABEL_KEY = "_builtin.measure.targetLabel";
    /** Binding parameter key holding the target test's ID. */
    public static final String TARGET_TEST_ID_KEY = "_builtin.measure.targetTestId";
    /** Binding parameter key holding the COUNT measure's tallied response result. */
    public static final String RESPONSE_RESULT_KEY = "_builtin.measure.responseResult";
    /** Binding parameter key holding the QA measure's pipe-delimited acceptable response results. */
    public static final String ACCEPTABLE_RESPONSE_RESULTS_KEY = "_builtin.measure.acceptableResponseResults";
    /** Binding parameter key holding the QA measure's pipe-delimited acceptable response statuses. */
    public static final String ACCEPTABLE_RESPONSE_STATUSES_KEY = "_builtin.measure.acceptableResponseStatuses";
    /** Metadata key under which the matching response count is recorded on a synthesized response. */
    public static final String MATCHING_COUNT_KEY = "_builtin.measure.matchingCount";
    /** Metadata key under which the total record count is recorded on a synthesized response. */
    public static final String TOTAL_RECORDS_KEY = "_builtin.measure.totalRecords";
    /** Metadata key under which the computed percentage is recorded on a synthesized response. */
    public static final String PERCENTAGE_KEY = "_builtin.measure.percentage";
    /** {@link TestDefinition#metadata()} key holding the test's expected-response specification text. */
    public static final String EXPECTED_RESPONSE_METADATA_KEY = "expectedResponse";
    /** {@link TestDefinition#metadata()} key holding a free-text note about the test. */
    public static final String NOTE_METADATA_KEY = "note";

    private static final List<String> SUPPORTED_RESPONSE_RESULTS = List.of(
            "EXTERNAL_PREREQUISITES_NOT_MET",
            "INTERNAL_PREREQUISITES_NOT_MET",
            "NOT_COMPLIANT",
            "NOT_COMPLETE",
            "NOT_AMENDED",
            "COMPLIANT",
            "COMPLETE",
            "AMENDED",
            "FILLED_IN");
    private static final Pattern COUNT_EXPECTED_RESPONSE = Pattern.compile(
            "Count\\s+the\\s+number\\s+of\\s+([A-Z0-9_]+).*?Response\\.result\\s*=\\s*([A-Z_]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QA_TARGET = Pattern.compile(
            "every\\s+([A-Z0-9_]+)\\s+in\\s+the\\s+MultiRecord",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QA_ALLOWED_RESPONSE = Pattern.compile(
            "Response\\.(result|status)\\s*=\\s*([A-Z_]+)",
            Pattern.CASE_INSENSITIVE);

    public BuiltInMeasureSpec {
        acceptableResponseResults = List.copyOf(acceptableResponseResults == null ? List.of() : acceptableResponseResults);
        acceptableResponseStatuses = List.copyOf(acceptableResponseStatuses == null ? List.of() : acceptableResponseStatuses);
    }

    /**
     * Parses a built-in measure specification from a test definition's label, if it follows the
     * {@code MULTIRECORD_MEASURE_COUNT_*} or {@code MULTIRECORD_MEASURE_QA_*} naming convention.
     *
     * @param test the test definition to inspect; must be of {@link TestType#MEASURE} and have a
     *     non-null label to match
     * @return the parsed spec, or {@link Optional#empty()} if {@code test} is null, not a
     *     MEASURE, has no label, or its label does not match a recognized built-in pattern
     */
    public static Optional<BuiltInMeasureSpec> from(TestDefinition test) {
        if (test == null || test.type() != TestType.MEASURE || test.label() == null) {
            return Optional.empty();
        }
        String label = test.label().trim();
        String expectedResponse = test.metadata().get(EXPECTED_RESPONSE_METADATA_KEY);
        if (label.startsWith("MULTIRECORD_MEASURE_COUNT_")) {
            return parseCountMeasure(label, expectedResponse);
        }
        if (label.startsWith("MULTIRECORD_MEASURE_QA_")) {
            return Optional.of(parseQaMeasure(label, expectedResponse));
        }
        return Optional.empty();
    }

    /**
     * Parses a COUNT measure from a {@code MULTIRECORD_MEASURE_COUNT_*} label, preferring the
     * structured expected-response text (if present and matching {@link #COUNT_EXPECTED_RESPONSE})
     * over parsing the label suffix directly.
     *
     * @param label the measure test's label, prefixed with {@code MULTIRECORD_MEASURE_COUNT_}
     * @param expectedResponse the test's expected-response metadata text, if any
     * @return the parsed COUNT spec, or {@link Optional#empty()} if neither the expected-response
     *     text nor the label suffix could be parsed
     */
    private static Optional<BuiltInMeasureSpec> parseCountMeasure(String label, String expectedResponse) {
        if (expectedResponse != null && !expectedResponse.isBlank()) {
            Matcher matcher = COUNT_EXPECTED_RESPONSE.matcher(expectedResponse);
            if (matcher.find()) {
                return Optional.of(new BuiltInMeasureSpec(
                        MeasureKind.COUNT,
                        matcher.group(1),
                        null,
                        matcher.group(2),
                        List.of(),
                        List.of()));
            }
        }
        String prefix = "MULTIRECORD_MEASURE_COUNT_";
        String remainder = label.substring(prefix.length());
        for (String candidate : SUPPORTED_RESPONSE_RESULTS) {
            String token = candidate + "_";
            if (remainder.startsWith(token) && remainder.length() > token.length()) {
                return Optional.of(new BuiltInMeasureSpec(
                        MeasureKind.COUNT,
                        "VALIDATION_" + remainder.substring(token.length()),
                        null,
                        candidate,
                        List.of(),
                        List.of()));
            }
        }
        return Optional.empty();
    }

    /**
     * Parses a QA measure from a {@code MULTIRECORD_MEASURE_QA_*} label, deriving the target
     * test label and the acceptable response results/statuses from the expected-response
     * metadata text when available, falling back to a default of
     * {@code Response.result=COMPLIANT} or {@code Response.status=INTERNAL_PREREQUISITES_NOT_MET}
     * when no criteria could be extracted.
     *
     * @param label the measure test's label, prefixed with {@code MULTIRECORD_MEASURE_QA_}
     * @param expectedResponse the test's expected-response metadata text, if any
     * @return the parsed QA spec
     */
    private static BuiltInMeasureSpec parseQaMeasure(String label, String expectedResponse) {
        String prefix = "MULTIRECORD_MEASURE_QA_";
        String targetLabel = "VALIDATION_" + label.substring(prefix.length());
        Set<String> acceptableResults = new LinkedHashSet<>();
        Set<String> acceptableStatuses = new LinkedHashSet<>();
        if (expectedResponse != null && !expectedResponse.isBlank()) {
            Matcher targetMatcher = QA_TARGET.matcher(expectedResponse);
            if (targetMatcher.find()) {
                targetLabel = targetMatcher.group(1);
            }
            Matcher allowedMatcher = QA_ALLOWED_RESPONSE.matcher(expectedResponse);
            while (allowedMatcher.find()) {
                String type = allowedMatcher.group(1);
                String value = allowedMatcher.group(2);
                if ("result".equalsIgnoreCase(type)) {
                    acceptableResults.add(value);
                } else {
                    acceptableStatuses.add(value);
                }
            }
        }
        if (acceptableResults.isEmpty() && acceptableStatuses.isEmpty()) {
            acceptableResults.add("COMPLIANT");
            acceptableStatuses.add("INTERNAL_PREREQUISITES_NOT_MET");
        }
        return new BuiltInMeasureSpec(
                MeasureKind.QA,
                targetLabel,
                null,
                null,
                List.copyOf(acceptableResults),
                List.copyOf(acceptableStatuses));
    }

    /**
     * Recovers a built-in measure specification from an already-bound {@link ImplementationBinding},
     * reading back the parameter values that {@link #asBindingParameters()} recorded on it.
     *
     * @param binding the binding to inspect
     * @return the recovered spec, or {@link Optional#empty()} if {@code binding} is not a
     *     built-in measure binding, as determined by {@link #isBuiltIn(ImplementationBinding)}
     */
    public static Optional<BuiltInMeasureSpec> from(ImplementationBinding binding) {
        if (!isBuiltIn(binding)) {
            return Optional.empty();
        }
        return Optional.of(new BuiltInMeasureSpec(
                MeasureKind.valueOf(binding.parameters().get(KIND_KEY)),
                binding.parameters().get(TARGET_LABEL_KEY),
                binding.parameters().get(TARGET_TEST_ID_KEY),
                binding.parameters().get(RESPONSE_RESULT_KEY),
                split(binding.parameters().get(ACCEPTABLE_RESPONSE_RESULTS_KEY)),
                split(binding.parameters().get(ACCEPTABLE_RESPONSE_STATUSES_KEY))));
    }

    /**
     * Determines whether an implementation binding represents a synthetic built-in measure
     * (as opposed to a discovered, reflection-based implementation), by checking that it targets
     * this class's implementation class/method and carries the required parameter keys.
     *
     * @param binding the binding to inspect, possibly null
     * @return {@code true} if {@code binding} is a built-in measure binding
     */
    public static boolean isBuiltIn(ImplementationBinding binding) {
        return binding != null
                && binding.testType() == TestType.MEASURE
                && IMPLEMENTATION_CLASS.equals(binding.implementationClass())
                && IMPLEMENTATION_METHOD.equals(binding.implementationMethod())
                && binding.parameters().containsKey(KIND_KEY)
                && binding.parameters().containsKey(TARGET_LABEL_KEY)
                && binding.parameters().containsKey(TARGET_TEST_ID_KEY)
                && (binding.parameters().containsKey(RESPONSE_RESULT_KEY)
                        || binding.parameters().containsKey(ACCEPTABLE_RESPONSE_RESULTS_KEY)
                        || binding.parameters().containsKey(ACCEPTABLE_RESPONSE_STATUSES_KEY));
    }

    /**
     * Renders this spec as the binding parameter map recorded on a synthetic
     * {@link ImplementationBinding}, using the {@code *_KEY} constants declared on this type so
     * that {@link #from(ImplementationBinding)} can later recover an equivalent spec.
     *
     * @return an immutable map of binding parameter keys to their string values
     */
    public Map<String, String> asBindingParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(KIND_KEY, kind.name());
        parameters.put(TARGET_LABEL_KEY, targetTestLabel);
        parameters.put(TARGET_TEST_ID_KEY, targetTestId);
        if (responseResult != null) {
            parameters.put(RESPONSE_RESULT_KEY, responseResult);
        }
        if (!acceptableResponseResults.isEmpty()) {
            parameters.put(ACCEPTABLE_RESPONSE_RESULTS_KEY, String.join("|", acceptableResponseResults));
        }
        if (!acceptableResponseStatuses.isEmpty()) {
            parameters.put(ACCEPTABLE_RESPONSE_STATUSES_KEY, String.join("|", acceptableResponseStatuses));
        }
        return Map.copyOf(parameters);
    }

    /**
     * Builds a human-readable description of what this measure computes, for use in reports and
     * diagnostics.
     *
     * @return a one-line description naming the target test and the counting/matching criteria
     */
    public String description() {
        if (kind == MeasureKind.COUNT) {
            return "Built-in multi-record COUNT measure for "
                    + targetTestLabel
                    + " with Response.result="
                    + responseResult;
        }
        List<String> criteria = new ArrayList<>();
        acceptableResponseResults.forEach(value -> criteria.add("Response.result=" + value));
        acceptableResponseStatuses.forEach(value -> criteria.add("Response.status=" + value));
        return "Built-in multi-record QA measure for "
                + targetTestLabel
                + " requiring "
                + String.join(" or ", criteria);
    }

    /**
     * Checks whether a response satisfies this QA measure's passing criteria.
     *
     * @param response the response to evaluate
     * @return {@code true} if {@code response}'s result or status is among this spec's
     *     {@link #acceptableResponseResults()} or {@link #acceptableResponseStatuses()}
     */
    public boolean matchesQaCondition(Response response) {
        return acceptableResponseResults.contains(response.responseResult())
                || acceptableResponseStatuses.contains(response.responseStatus());
    }

    /**
     * Splits a pipe-delimited binding parameter value back into its individual trimmed tokens.
     *
     * @param value the pipe-delimited value, possibly null or blank
     * @return the non-blank, trimmed tokens, or an empty list if {@code value} is null or blank
     */
    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\|")).stream()
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }

    /** Distinguishes the two kinds of built-in multi-record measure this spec can represent. */
    public enum MeasureKind {
        /** Tallies how many responses for the target test matched a specific response result. */
        COUNT,
        /** Checks each response for the target test against a set of acceptable results/statuses. */
        QA
    }
}
