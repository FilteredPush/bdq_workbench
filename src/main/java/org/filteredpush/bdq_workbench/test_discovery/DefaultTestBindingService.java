package org.filteredpush.bdq_workbench.test_discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.ImplementationStatus;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default binding strategy preferring @Provides IDs, then explicit mappings. */
public class DefaultTestBindingService implements TestBindingService {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultTestBindingService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    @Override
    public TestBindingResult bind(
            List<TestDefinition> tests,
            List<DiscoveredImplementation> discovered,
            Map<String, String> explicitMapping) {
        return bind(tests, discovered, explicitMapping, Set.of());
    }

    @Override
    public TestBindingResult bind(
            List<TestDefinition> tests,
            List<DiscoveredImplementation> discovered,
            Map<String, String> explicitMapping,
            Collection<String> availableTerms) {

        LOG.debug("Binding {} tests to {} discovered implementations with {} explicit mappings",
                tests.size(), discovered.size(), explicitMapping.size());

        Map<String, List<DiscoveredImplementation>> byProvided = discovered.stream()
                .filter(d -> d.providedTestId() != null && !d.providedTestId().isBlank())
                .collect(Collectors.groupingBy(d -> normalize(d.providedTestId())));
        Map<String, List<DiscoveredImplementation>> byVersion = discovered.stream()
                .filter(d -> d.providedVersion() != null && !d.providedVersion().isBlank())
                .collect(Collectors.groupingBy(d -> normalize(d.providedVersion())));
        Map<String, DiscoveredImplementation> byMethodKey = discovered.stream()
                .collect(Collectors.toMap(
                        d -> d.implementationClass() + "#" + d.implementationMethod(),
                        Function.identity(),
                        (a, b) -> a));

        Map<String, String> availableTermsByAlias = indexAvailableTerms(availableTerms);
        List<ImplementationBinding> bindings = new ArrayList<>();
        List<TestDefinition> unresolved = new ArrayList<>();
        List<BindingReview> reviews = new ArrayList<>();

        for (TestDefinition test : tests) {
            Selection selection = selectCandidate(test, explicitMapping, byMethodKey, byProvided, byVersion);
            if (selection.candidates().isEmpty()) {
                unresolved.add(test);
                reviews.add(new BindingReview(
                        test,
                        ImplementationStatus.MISSING,
                        BindingStatus.UNBOUND,
                        ParameterizationCapability.DEFAULT_ONLY,
                        "",
                        Map.copyOf(test.parameters()),
                        test.parameters().isEmpty(),
                        List.of("No implementation discovered for " + test.id())));
                continue;
            }

            List<String> diagnostics = new ArrayList<>(selection.diagnostics());
            ParameterizationCapability capability = determineCapability(selection.candidates());
            ImplementationStatus implementationStatus =
                    selection.candidates().size() > 1 ? ImplementationStatus.AMBIGUOUS : ImplementationStatus.FOUND;

            CandidateEvaluation evaluation = evaluateCandidate(
                    test,
                    selection.chosen(),
                    capability,
                    selection.selectionReason(),
                    availableTermsByAlias);
            bindings.add(evaluation.binding());
            if (evaluation.binding().bindingStatus() != BindingStatus.BOUND) {
                unresolved.add(test);
            }
            diagnostics.addAll(evaluation.binding().diagnostics());
            reviews.add(new BindingReview(
                    test,
                    implementationStatus,
                    evaluation.binding().bindingStatus(),
                    capability,
                    evaluation.binding().implementationClass() + "#" + evaluation.binding().implementationMethod(),
                    evaluation.binding().parameters(),
                    evaluation.binding().usingDefaultParameters(),
                    List.copyOf(diagnostics)));
        }

        return new TestBindingResult(List.copyOf(bindings), List.copyOf(unresolved), List.copyOf(reviews));
    }

    private CandidateEvaluation evaluateCandidate(
            TestDefinition test,
            DiscoveredImplementation chosen,
            ParameterizationCapability capability,
            String selectionReason,
            Map<String, String> availableTermsByAlias) {
        List<BoundMethodParameter> boundParameters = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        BindingStatus status = BindingStatus.BOUND;
        Map<String, String> parameterValues = new LinkedHashMap<>();

        for (MethodParameter parameter : chosen.parameters()) {
            BoundMethodParameter bound = bindParameter(test, parameter, availableTermsByAlias);
            boundParameters.add(bound);
            if (bound.suppliedValue() != null && parameter.role() == ParameterRole.PARAMETER) {
                parameterValues.put(parameter.source(), bound.suppliedValue());
            }
            if (!bound.bound()) {
                diagnostics.add(bound.reason());
                status = status == BindingStatus.BOUND ? BindingStatus.PARTIAL : status;
                if (parameter.required()) {
                    status = BindingStatus.UNBOUND;
                }
            }
        }
        if (status == BindingStatus.BOUND) {
            diagnostics.add("BOUND: all parameters compatible");
        }

        ImplementationBinding binding = new ImplementationBinding(
                test.id(),
                chosen.testType() == TestType.UNKNOWN ? test.type() : chosen.testType(),
                chosen.implementationClass(),
                chosen.implementationMethod(),
                chosen.phase(),
                Map.copyOf(parameterValues),
                status,
                capability,
                selectionReason,
                parameterValues.isEmpty(),
                List.copyOf(boundParameters),
                List.copyOf(diagnostics));
        return new CandidateEvaluation(binding);
    }

    private BoundMethodParameter bindParameter(
            TestDefinition test,
            MethodParameter parameter,
            Map<String, String> availableTermsByAlias) {
        if (parameter.role() == ParameterRole.LEGACY_RECORD || parameter.role() == ParameterRole.LEGACY_PARAMETERS) {
            return new BoundMethodParameter(parameter, parameter.source(), null, true, "Legacy compatibility binding");
        }
        if (parameter.role() == ParameterRole.PARAMETER) {
            String providedValue = resolveParameterValue(test.parameters(), parameter.source());
            if (providedValue == null) {
                if (canUseImplementationDefault(parameter)) {
                    return new BoundMethodParameter(
                            parameter,
                            parameter.source(),
                            null,
                            true,
                            "No parameter value supplied for " + parameter.source()
                                    + "; invoking with null to allow implementation defaults");
                }
                return new BoundMethodParameter(
                        parameter,
                        parameter.source(),
                        null,
                        false,
                        "Missing parameter value for " + parameter.source());
            }
            if (!isSupportedScalarType(parameter.typeName())) {
                return new BoundMethodParameter(
                        parameter,
                        parameter.source(),
                        providedValue,
                        false,
                        "Unsupported parameter type " + parameter.typeName() + " for " + parameter.source());
            }
            return new BoundMethodParameter(parameter, parameter.source(), providedValue, true, "Parameter provided");
        }
        String resolvedField = resolveTerm(parameter.source(), availableTermsByAlias);
        if (resolvedField == null) {
            return new BoundMethodParameter(
                    parameter,
                    parameter.source(),
                    null,
                    false,
                    "Missing " + parameter.role().name().toLowerCase() + " term " + parameter.source());
        }
        if (!isSupportedScalarType(parameter.typeName())) {
            return new BoundMethodParameter(
                    parameter,
                    resolvedField,
                    null,
                    false,
                    "Unsupported parameter type " + parameter.typeName() + " for term " + parameter.source());
        }
        return new BoundMethodParameter(parameter, resolvedField, null, true, "Mapped to " + resolvedField);
    }

    private static String resolveParameterValue(Map<String, String> parameters, String parameterName) {
        if (parameters.containsKey(parameterName)) {
            return parameters.get(parameterName);
        }
        String normalized = normalizeTerm(parameterName);
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (normalizeTerm(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Selection selectCandidate(
            TestDefinition test,
            Map<String, String> explicitMapping,
            Map<String, DiscoveredImplementation> byMethodKey,
            Map<String, List<DiscoveredImplementation>> byProvided,
            Map<String, List<DiscoveredImplementation>> byVersion) {
        List<String> diagnostics = new ArrayList<>();
        List<DiscoveredImplementation> candidates = lookupCandidates(test, byProvided, byVersion, diagnostics);

        String mappedMethod = explicitMapping.get(test.id());
        if (mappedMethod != null && byMethodKey.containsKey(mappedMethod)) {
            diagnostics.add("Explicit mapping selected " + mappedMethod);
            return new Selection(List.of(byMethodKey.get(mappedMethod)), byMethodKey.get(mappedMethod), "explicit mapping", diagnostics);
        }
        if (candidates.isEmpty()) {
            return new Selection(List.of(), null, "no match", diagnostics);
        }

        List<DiscoveredImplementation> defaultCandidates = candidates.stream()
                .filter(candidate -> !candidate.isParameterized())
                .sorted(implementationComparator())
                .toList();
        List<DiscoveredImplementation> parameterizedCandidates = candidates.stream()
                .filter(DiscoveredImplementation::isParameterized)
                .sorted(implementationComparator())
                .toList();
        boolean hasUserParameters = !test.parameters().isEmpty();

        DiscoveredImplementation chosen;
        String reason;
        if (hasUserParameters && !parameterizedCandidates.isEmpty()) {
            chosen = parameterizedCandidates.get(0);
            reason = "parameterized method selected because parameter values were provided";
        } else if (!hasUserParameters && !defaultCandidates.isEmpty()) {
            chosen = defaultCandidates.get(0);
            reason = "default method selected because no parameter values were provided";
        } else if (!parameterizedCandidates.isEmpty()) {
            chosen = parameterizedCandidates.get(0);
            reason = "parameterized-only implementation available";
        } else {
            chosen = defaultCandidates.get(0);
            reason = "default-only implementation available";
        }

        if (candidates.size() > 1) {
            diagnostics.add("Ambiguous candidates resolved deterministically by class and method ordering");
        }
        diagnostics.add(reason);
        return new Selection(candidates, chosen, reason, diagnostics);
    }

    private static List<DiscoveredImplementation> lookupCandidates(
            TestDefinition test,
            Map<String, List<DiscoveredImplementation>> byProvided,
            Map<String, List<DiscoveredImplementation>> byVersion,
            List<String> diagnostics) {
        String normalizedTestId = normalize(test.id());
        List<DiscoveredImplementation> direct = byVersion.get(normalizedTestId);
        if (direct == null || direct.isEmpty()) {
            direct = byProvided.get(normalizedTestId);
        }
        if (direct == null || direct.isEmpty()) {
            String providedFallbackKey = toProvidesKey(normalizedTestId);
            if (providedFallbackKey != null) {
                direct = byProvided.get(providedFallbackKey);
                if (direct != null && !direct.isEmpty()) {
                    diagnostics.add("Matched by @Provides fallback after no exact @ProvidesVersion match");
                }
            }
        }
        return direct == null ? List.of() : direct;
    }

    private static Comparator<DiscoveredImplementation> implementationComparator() {
        return Comparator.comparing(DiscoveredImplementation::implementationClass)
                .thenComparing(DiscoveredImplementation::implementationMethod);
    }

    private static ParameterizationCapability determineCapability(List<DiscoveredImplementation> candidates) {
        boolean hasDefault = candidates.stream().anyMatch(candidate -> !candidate.isParameterized());
        boolean hasParameterized = candidates.stream().anyMatch(DiscoveredImplementation::isParameterized);
        if (hasDefault && hasParameterized) {
            return ParameterizationCapability.BOTH;
        }
        return hasParameterized ? ParameterizationCapability.PARAMETERIZED_ONLY : ParameterizationCapability.DEFAULT_ONLY;
    }

    private static boolean isSupportedScalarType(String typeName) {
        return typeName.equals(String.class.getName())
                || typeName.equals(Integer.class.getName())
                || typeName.equals("int")
                || typeName.equals(Long.class.getName())
                || typeName.equals("long")
                || typeName.equals(Double.class.getName())
                || typeName.equals("double")
                || typeName.equals(Boolean.class.getName())
                || typeName.equals("boolean")
                || typeName.equals(Float.class.getName())
                || typeName.equals("float");
    }

    private static boolean canUseImplementationDefault(MethodParameter parameter) {
        return isSupportedScalarType(parameter.typeName()) && !isPrimitiveType(parameter.typeName());
    }

    private static boolean isPrimitiveType(String typeName) {
        return typeName.equals("int")
                || typeName.equals("long")
                || typeName.equals("double")
                || typeName.equals("boolean")
                || typeName.equals("float");
    }

    private static Map<String, String> indexAvailableTerms(Collection<String> availableTerms) {
        Map<String, String> byAlias = new LinkedHashMap<>();
        availableTerms.stream().sorted().forEach(term -> {
            byAlias.putIfAbsent(normalizeTerm(term), term);
            byAlias.putIfAbsent(normalizeTerm(localName(term)), term);
        });
        return byAlias;
    }

    private static String resolveTerm(String requested, Map<String, String> availableTermsByAlias) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String exact = availableTermsByAlias.get(normalizeTerm(requested));
        if (exact != null) {
            return exact;
        }
        return availableTermsByAlias.get(normalizeTerm(localName(requested)));
    }

    private static String localName(String value) {
        if (value == null) {
            return "";
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('#'));
        int colon = value.lastIndexOf(':');
        int index = Math.max(slash, colon);
        return index >= 0 && index + 1 < value.length() ? value.substring(index + 1) : value;
    }

    private static String normalizeTerm(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String normalize(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return null;
        }
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String toProvidesKey(String testId) {
        if (testId == null || testId.isBlank()) {
            return null;
        }
        Matcher matcher = UUID_PATTERN.matcher(testId);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return testId;
    }

    private record Selection(
            List<DiscoveredImplementation> candidates,
            DiscoveredImplementation chosen,
            String selectionReason,
            List<String> diagnostics) {
    }

    private record CandidateEvaluation(ImplementationBinding binding) {
    }
}
