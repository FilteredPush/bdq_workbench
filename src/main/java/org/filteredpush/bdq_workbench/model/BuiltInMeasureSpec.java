package org.filteredpush.bdq_workbench.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Parsed metadata for built-in multi-record measures synthesized from response streams. */
public record BuiltInMeasureSpec(String targetTestLabel, String targetTestId, String responseResult) {
    public static final String IMPLEMENTATION_CLASS = BuiltInMeasureSpec.class.getName();
    public static final String IMPLEMENTATION_METHOD = "countMatchingResponseResult";
    public static final String TARGET_LABEL_KEY = "_builtin.measure.targetLabel";
    public static final String TARGET_TEST_ID_KEY = "_builtin.measure.targetTestId";
    public static final String RESPONSE_RESULT_KEY = "_builtin.measure.responseResult";

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

    public static Optional<BuiltInMeasureSpec> from(TestDefinition test) {
        if (test == null || test.type() != TestType.MEASURE || test.label() == null) {
            return Optional.empty();
        }
        String prefix = "MULTIRECORD_MEASURE_COUNT_";
        String label = test.label().trim();
        if (!label.startsWith(prefix)) {
            return Optional.empty();
        }
        String remainder = label.substring(prefix.length());
        for (String candidate : SUPPORTED_RESPONSE_RESULTS) {
            String token = candidate + "_";
            if (remainder.startsWith(token) && remainder.length() > token.length()) {
                return Optional.of(new BuiltInMeasureSpec(
                        "VALIDATION_" + remainder.substring(token.length()),
                        null,
                        candidate));
            }
        }
        return Optional.empty();
    }

    public static Optional<BuiltInMeasureSpec> from(ImplementationBinding binding) {
        if (!isBuiltIn(binding)) {
            return Optional.empty();
        }
        return Optional.of(new BuiltInMeasureSpec(
                binding.parameters().get(TARGET_LABEL_KEY),
                binding.parameters().get(TARGET_TEST_ID_KEY),
                binding.parameters().get(RESPONSE_RESULT_KEY)));
    }

    public static boolean isBuiltIn(ImplementationBinding binding) {
        return binding != null
                && binding.testType() == TestType.MEASURE
                && IMPLEMENTATION_CLASS.equals(binding.implementationClass())
                && IMPLEMENTATION_METHOD.equals(binding.implementationMethod())
                && binding.parameters().containsKey(TARGET_LABEL_KEY)
                && binding.parameters().containsKey(TARGET_TEST_ID_KEY)
                && binding.parameters().containsKey(RESPONSE_RESULT_KEY);
    }

    public Map<String, String> asBindingParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(TARGET_LABEL_KEY, targetTestLabel);
        parameters.put(TARGET_TEST_ID_KEY, targetTestId);
        parameters.put(RESPONSE_RESULT_KEY, responseResult);
        return Map.copyOf(parameters);
    }

    public String description() {
        return "Built-in multi-record COUNT measure for "
                + targetTestLabel
                + " with Response.result="
                + responseResult;
    }
}
