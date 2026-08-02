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

/** Parsed metadata for built-in multi-record measures synthesized from response streams. */
public record BuiltInMeasureSpec(
        MeasureKind kind,
        String targetTestLabel,
        String targetTestId,
        String responseResult,
        List<String> acceptableResponseResults,
        List<String> acceptableResponseStatuses) {
    public static final String IMPLEMENTATION_CLASS = BuiltInMeasureSpec.class.getName();
    public static final String IMPLEMENTATION_METHOD = "evaluateBuiltInMeasure";
    public static final String KIND_KEY = "_builtin.measure.kind";
    public static final String MEASURE_LABEL_KEY = "_builtin.measure.label";
    public static final String TARGET_LABEL_KEY = "_builtin.measure.targetLabel";
    public static final String TARGET_TEST_ID_KEY = "_builtin.measure.targetTestId";
    public static final String RESPONSE_RESULT_KEY = "_builtin.measure.responseResult";
    public static final String ACCEPTABLE_RESPONSE_RESULTS_KEY = "_builtin.measure.acceptableResponseResults";
    public static final String ACCEPTABLE_RESPONSE_STATUSES_KEY = "_builtin.measure.acceptableResponseStatuses";
    public static final String MATCHING_COUNT_KEY = "_builtin.measure.matchingCount";
    public static final String TOTAL_RECORDS_KEY = "_builtin.measure.totalRecords";
    public static final String PERCENTAGE_KEY = "_builtin.measure.percentage";
    public static final String EXPECTED_RESPONSE_METADATA_KEY = "expectedResponse";
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

    public boolean matchesQaCondition(Response response) {
        return acceptableResponseResults.contains(response.responseResult())
                || acceptableResponseStatuses.contains(response.responseStatus());
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\|")).stream()
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }

    public enum MeasureKind {
        COUNT,
        QA
    }
}
