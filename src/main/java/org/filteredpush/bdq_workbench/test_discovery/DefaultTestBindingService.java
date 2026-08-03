/** DefaultTestBindingService.java
 *
 * Default strategy for binding a use case's resolved tests to discovered implementation methods.
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
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
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

/**
 * Default binding strategy preferring @Provides IDs, then explicit mappings.
 *
 * <p>For each {@link TestDefinition} from a use case's resolved policy, this service selects a
 * {@link DiscoveredImplementation} to execute it with, in the following priority order:
 *
 * <ol>
 *   <li>Built-in multi-record measures — tests whose label begins with
 *       {@code MULTIRECORD_MEASURE_COUNT_} or {@code MULTIRECORD_MEASURE_QA_} are recognized via
 *       {@link BuiltInMeasureSpec#from(TestDefinition)} and bound to the synthetic
 *       {@link BuiltInMeasureSpec#IMPLEMENTATION_CLASS}/{@link BuiltInMeasureSpec#IMPLEMENTATION_METHOD}
 *       handle rather than a discovered method, targeting the {@code VALIDATION} test named in
 *       the measure's label.
 *   <li>An explicit {@code testId -> implementationClass#implementationMethod} mapping entry, if
 *       one is supplied and matches a discovered method — this overrides automatic selection
 *       entirely.
 *   <li>Automatic candidate lookup by version-qualified {@code @ProvidesVersion} identifier,
 *       falling back to plain {@code @Provides} identifier (matched directly, or, for
 *       UUID-bearing test IDs, by the embedded UUID) — see {@link #lookupCandidates}.
 * </ol>
 *
 * <p>Among the resulting candidates, a parameterized implementation (one with at least one
 * user-supplied {@code @Parameter}) is preferred when the test itself supplies parameter values;
 * otherwise a default (unparameterized) implementation is preferred. Once an implementation is
 * chosen, each of its reflected parameters is bound via {@link #bindParameter}: acted-upon and
 * consulted parameters are matched against the dataset's available terms (by exact name or local
 * name, case-insensitively), user parameters are matched against the test's supplied parameter
 * values, and legacy positional parameters are passed through unresolved for the execution layer
 * to populate. The resulting {@link org.filteredpush.bdq_workbench.model.BindingStatus} reflects
 * whether every required parameter could be bound, or whether some are missing an acted-upon or
 * consulted term.
 *
 * @see TestBindingService
 * @see DiscoveredImplementation
 */
public class DefaultTestBindingService implements TestBindingService {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultTestBindingService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    /**
     * Binds each policy test to a discovered implementation without regard to which terms are
     * present in the dataset.
     *
     * <p>Delegates to {@link #bind(List, List, Map, Collection)} with an empty set of available
     * terms, so every acted-upon/consulted parameter will be reported as bound (no term-presence
     * check is performed).
     *
     * @param tests the resolved tests from a use case's policy
     * @param discovered the implementations located by a {@link TestDiscoveryService}
     * @param explicitMapping test ID to {@code implementationClass#implementationMethod} overrides
     * @return the bindings, unresolved tests, and diagnostic reviews produced by this attempt
     */
    @Override
    public TestBindingResult bind(
            List<TestDefinition> tests,
            List<DiscoveredImplementation> discovered,
            Map<String, String> explicitMapping) {
        return bind(tests, discovered, explicitMapping, Set.of());
    }

    /**
     * Binds each policy test to a discovered implementation, checking acted-upon/consulted
     * parameters against the given available terms.
     *
     * <p>Iterates {@code tests} in order, first checking whether each is a built-in multi-record
     * measure (bound directly against its target validation test's ID), then otherwise selecting
     * a candidate implementation via {@link #selectCandidate} and evaluating its parameter
     * bindings via {@link #evaluateCandidate}. A test with no matching candidate, or whose
     * built-in measure target cannot be resolved, is added to the result's {@code unresolved}
     * list; every attempted test — resolved or not — produces one {@link BindingReview}.
     *
     * @param tests the resolved tests from a use case's policy
     * @param discovered the implementations located by a {@link TestDiscoveryService}
     * @param explicitMapping test ID to {@code implementationClass#implementationMethod} overrides
     *     that take precedence over automatic candidate selection
     * @param availableTerms the Darwin Core term names present in the dataset being processed,
     *     used to check acted-upon/consulted parameter availability
     * @return the bindings, unresolved tests, and diagnostic reviews produced by this attempt
     */
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
        Map<String, TestDefinition> validationTestsByLabel = tests.stream()
                .filter(test -> test.type() == TestType.VALIDATION)
                .filter(test -> test.label() != null && !test.label().isBlank())
                .collect(Collectors.toMap(
                        TestDefinition::label,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        for (TestDefinition test : tests) {
            var builtInMeasure = BuiltInMeasureSpec.from(test);
            if (builtInMeasure.isPresent()) {
                TestDefinition targetTest = validationTestsByLabel.get(builtInMeasure.get().targetTestLabel());
                if (targetTest == null) {
                    unresolved.add(test);
                    reviews.add(new BindingReview(
                            test,
                            ImplementationStatus.MISSING,
                            BindingStatus.UNBOUND,
                            ParameterizationCapability.DEFAULT_ONLY,
                            "",
                            Map.of(),
                            true,
                            List.of("No validation test found for built-in multi-record measure target "
                                    + builtInMeasure.get().targetTestLabel())));
                    continue;
                }
                BuiltInMeasureSpec resolvedMeasure = new BuiltInMeasureSpec(
                        builtInMeasure.get().kind(),
                        builtInMeasure.get().targetTestLabel(),
                        targetTest.id(),
                        builtInMeasure.get().responseResult(),
                        builtInMeasure.get().acceptableResponseResults(),
                        builtInMeasure.get().acceptableResponseStatuses());
                Map<String, String> bindingParameters = new LinkedHashMap<>(resolvedMeasure.asBindingParameters());
                bindingParameters.put(BuiltInMeasureSpec.MEASURE_LABEL_KEY, test.label());
                List<String> diagnostics = List.of(
                        "Built-in multi-record " + resolvedMeasure.kind().name() + " measure",
                        resolvedMeasure.description());
                bindings.add(new ImplementationBinding(
                        test.id(),
                        test.type(),
                        BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
                        BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
                        test.phase(),
                        Map.copyOf(bindingParameters),
                        BindingStatus.BOUND,
                        ParameterizationCapability.DEFAULT_ONLY,
                        "built-in multi-record " + resolvedMeasure.kind().name().toLowerCase(),
                        true,
                        List.of(),
                        diagnostics));
                reviews.add(new BindingReview(
                        test,
                        ImplementationStatus.FOUND,
                        BindingStatus.BOUND,
                        ParameterizationCapability.DEFAULT_ONLY,
                        BuiltInMeasureSpec.IMPLEMENTATION_CLASS + "#" + BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
                        Map.of(),
                        true,
                        diagnostics));
                continue;
            }
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
                    selection.ambiguous() ? ImplementationStatus.AMBIGUOUS : ImplementationStatus.FOUND;

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

    /**
     * Binds every reflected parameter of the chosen implementation and derives the overall
     * {@link org.filteredpush.bdq_workbench.model.BindingStatus} for the test.
     *
     * <p>The status starts as {@code BOUND} and is downgraded as unbound parameters are found:
     * a missing acted-upon/consulted term downgrades to {@code TERM_MISSING} (or leaves
     * {@code UNBOUND} if already set), any other unbound optional parameter downgrades to
     * {@code PARTIAL}, and any unbound required parameter (other than a missing term) forces
     * {@code UNBOUND}.
     *
     * @param test the policy test being bound
     * @param chosen the implementation selected by {@link #selectCandidate}
     * @param capability whether the candidate set offered default and/or parameterized methods
     * @param selectionReason human-readable explanation of why {@code chosen} was selected
     * @param availableTermsByAlias available dataset terms indexed by normalized alias, as built
     *     by {@link #indexAvailableTerms}
     * @return the resulting {@link ImplementationBinding} wrapped for further diagnostics
     */
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
                if (isTermMissing(bound)) {
                    status = status == BindingStatus.UNBOUND ? status : BindingStatus.TERM_MISSING;
                } else {
                    status = status == BindingStatus.BOUND ? BindingStatus.PARTIAL : status;
                }
                if (parameter.required() && !isTermMissing(bound)) {
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

    /**
     * Resolves a single reflected method parameter to a source value or dataset term.
     *
     * <p>Legacy positional parameters ({@link ParameterRole#LEGACY_RECORD}/
     * {@link ParameterRole#LEGACY_PARAMETERS}) are always reported as bound, since the execution
     * layer supplies them directly. {@link ParameterRole#PARAMETER} parameters are resolved
     * against the test's supplied parameter values (falling back to the implementation default
     * when unsupplied and the type allows it). All other parameters (acted-upon/consulted terms)
     * are resolved against the dataset's available terms via {@link #resolveTerm}.
     *
     * @param test the policy test supplying parameter values
     * @param parameter the reflected parameter being bound
     * @param availableTermsByAlias available dataset terms indexed by normalized alias
     * @return the binding decision for this parameter, including whether it was bound and why
     */
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
                    "TERM MISSING: Term acted_upon/consulted absent in input data: " + parameter.source());
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

    /**
     * Looks up a user-supplied parameter value by name, tolerating differences in case and
     * surrounding whitespace when the exact key is not present.
     *
     * @param parameters the test's supplied parameter values, keyed by parameter name
     * @param parameterName the parameter name to look up
     * @return the matching value, or {@code null} if no key matches even after normalization
     */
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

    /**
     * Chooses which discovered implementation, if any, a test should be bound to.
     *
     * <p>An explicit mapping entry for the test's ID, if present and resolvable via
     * {@code byMethodKey}, wins outright. Otherwise, candidates are looked up via
     * {@link #lookupCandidates}; among them, a parameterized implementation is preferred when
     * the test supplies parameter values, and a default (unparameterized) implementation is
     * preferred when it does not, falling back to whichever kind is available. A candidate set
     * of more than one implementation is considered ambiguous unless it is exactly one default
     * and one parameterized variant of the same test (a common, expected pairing).
     *
     * @param test the policy test being bound
     * @param explicitMapping test ID to {@code implementationClass#implementationMethod} overrides
     * @param byMethodKey discovered implementations indexed by
     *     {@code implementationClass#implementationMethod}
     * @param byProvided discovered implementations indexed by normalized {@code @Provides} ID
     * @param byVersion discovered implementations indexed by normalized {@code @ProvidesVersion} ID
     * @return the selection outcome: the full candidate set, the chosen implementation (if any),
     *     the reason it was chosen, diagnostics, and whether the candidate set was ambiguous
     */
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
            return new Selection(
                    List.of(byMethodKey.get(mappedMethod)),
                    byMethodKey.get(mappedMethod),
                    "explicit mapping",
                    diagnostics,
                    false);
        }
        if (candidates.isEmpty()) {
            return new Selection(List.of(), null, "no match", diagnostics, false);
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
        boolean parameterizedVersionAvailable = !defaultCandidates.isEmpty() && !parameterizedCandidates.isEmpty();

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

        boolean ambiguous = candidates.size() > 1 && !parameterizedVariantPair(defaultCandidates, parameterizedCandidates);
        if (parameterizedVersionAvailable) {
            diagnostics.add("Parameterized version available");
        }
        if (ambiguous) {
            diagnostics.add("Ambiguous candidates resolved deterministically by class and method ordering");
        }
        diagnostics.add(reason);
        return new Selection(candidates, chosen, reason, diagnostics, ambiguous);
    }

    /**
     * Looks up discovered implementations matching a test's identifier.
     *
     * <p>Tries, in order: an exact match on normalized {@code @ProvidesVersion}; if none, an
     * exact match on normalized {@code @Provides}; if still none and the test ID contains a
     * UUID, a fallback match on {@code @Provides} keyed by just that UUID (recording a
     * diagnostic when this fallback is what produced the match).
     *
     * @param test the policy test being bound
     * @param byProvided discovered implementations indexed by normalized {@code @Provides} ID
     * @param byVersion discovered implementations indexed by normalized {@code @ProvidesVersion} ID
     * @param diagnostics mutable list that fallback-match diagnostics are appended to
     * @return the matching candidates, or an empty list if none match
     */
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

    /**
     * Orders candidates deterministically by implementation class name, then method name, so
     * that selection among otherwise-equivalent candidates is stable and reproducible.
     *
     * @return a comparator ordering by implementation class, then implementation method
     */
    private static Comparator<DiscoveredImplementation> implementationComparator() {
        return Comparator.comparing(DiscoveredImplementation::implementationClass)
                .thenComparing(DiscoveredImplementation::implementationMethod);
    }

    /**
     * Determines whether a candidate set offers only default implementations, only
     * parameterized implementations, or both.
     *
     * @param candidates the candidate implementations for a test
     * @return {@link ParameterizationCapability#BOTH} if both kinds are present;
     *     {@link ParameterizationCapability#PARAMETERIZED_ONLY} or
     *     {@link ParameterizationCapability#DEFAULT_ONLY} otherwise
     */
    private static ParameterizationCapability determineCapability(List<DiscoveredImplementation> candidates) {
        boolean hasDefault = candidates.stream().anyMatch(candidate -> !candidate.isParameterized());
        boolean hasParameterized = candidates.stream().anyMatch(DiscoveredImplementation::isParameterized);
        if (hasDefault && hasParameterized) {
            return ParameterizationCapability.BOTH;
        }
        return hasParameterized ? ParameterizationCapability.PARAMETERIZED_ONLY : ParameterizationCapability.DEFAULT_ONLY;
    }

    /**
     * Reports whether a candidate set is the common, expected pairing of exactly one default
     * implementation and one parameterized implementation for the same test — which is not
     * treated as ambiguous even though it contains more than one candidate.
     *
     * @param defaultCandidates the candidates with no user-supplied parameters
     * @param parameterizedCandidates the candidates with at least one user-supplied parameter
     * @return {@code true} if there is exactly one of each
     */
    private static boolean parameterizedVariantPair(
            List<DiscoveredImplementation> defaultCandidates,
            List<DiscoveredImplementation> parameterizedCandidates) {
        return defaultCandidates.size() == 1 && parameterizedCandidates.size() == 1;
    }

    /**
     * Reports whether a reflected parameter's type is one this binder can convert a supplied
     * string value into.
     *
     * @param typeName the fully qualified (or primitive) type name of the parameter
     * @return {@code true} if the type is {@code String}, a supported boxed/primitive numeric or
     *     boolean type
     */
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

    /**
     * Reports whether an unsupplied {@code @Parameter} may safely be invoked with {@code null},
     * allowing the implementation method's own default to apply.
     *
     * <p>Only boxed (non-primitive) supported scalar types are eligible, since a primitive
     * parameter cannot accept {@code null}.
     *
     * @param parameter the reflected parameter metadata
     * @return {@code true} if the parameter's type is a supported boxed scalar type
     */
    private static boolean canUseImplementationDefault(MethodParameter parameter) {
        return isSupportedScalarType(parameter.typeName()) && !isPrimitiveType(parameter.typeName());
    }

    /**
     * Reports whether an unbound parameter's binding failure was specifically due to a missing
     * acted-upon or consulted term in the dataset, as opposed to some other binding failure.
     *
     * @param parameter the bound (or failed-to-bind) parameter
     * @return {@code true} if the parameter's role is acted-upon or consulted and its failure
     *     reason is a "TERM MISSING" diagnostic
     */
    private static boolean isTermMissing(BoundMethodParameter parameter) {
        return (parameter.parameter().role() == ParameterRole.ACTED_UPON
                        || parameter.parameter().role() == ParameterRole.CONSULTED)
                && parameter.reason().startsWith("TERM MISSING:");
    }

    /**
     * Reports whether a type name denotes a Java primitive.
     *
     * @param typeName the type name to check
     * @return {@code true} if {@code typeName} is one of {@code int}, {@code long},
     *     {@code double}, {@code boolean}, or {@code float}
     */
    private static boolean isPrimitiveType(String typeName) {
        return typeName.equals("int")
                || typeName.equals("long")
                || typeName.equals("double")
                || typeName.equals("boolean")
                || typeName.equals("float");
    }

    /**
     * Builds a lookup of available dataset terms indexed by normalized alias, so that a
     * requested term can be matched by its full name or by its local (unqualified) name,
     * case-insensitively.
     *
     * <p>Terms are processed in sorted order so that, when two terms would normalize to the
     * same alias, the alphabetically first is kept (via {@code putIfAbsent}).
     *
     * @param availableTerms the Darwin Core term names present in the dataset
     * @return available terms indexed by normalized full name and by normalized local name
     */
    private static Map<String, String> indexAvailableTerms(Collection<String> availableTerms) {
        Map<String, String> byAlias = new LinkedHashMap<>();
        availableTerms.stream().sorted().forEach(term -> {
            byAlias.putIfAbsent(normalizeTerm(term), term);
            byAlias.putIfAbsent(normalizeTerm(localName(term)), term);
        });
        return byAlias;
    }

    /**
     * Resolves a requested acted-upon/consulted term name to the actual term name present in
     * the dataset.
     *
     * @param requested the term name (typically a full Darwin Core IRI) the implementation
     *     requires
     * @param availableTermsByAlias available dataset terms indexed by normalized alias, as
     *     built by {@link #indexAvailableTerms}
     * @return the matching dataset term name, or {@code null} if neither the full name nor the
     *     local name matches (or {@code requested} is null or blank)
     */
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

    /**
     * Extracts the local (unqualified) name from a term identifier, taking everything after the
     * last {@code /}, {@code #}, or {@code :}, whichever occurs latest.
     *
     * @param value the term identifier, typically a full IRI or CURIE
     * @return the local name portion, or {@code value} itself (or {@code ""} if null) if it
     *     contains no recognized separator
     */
    private static String localName(String value) {
        if (value == null) {
            return "";
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('#'));
        int colon = value.lastIndexOf(':');
        int index = Math.max(slash, colon);
        return index >= 0 && index + 1 < value.length() ? value.substring(index + 1) : value;
    }

    /**
     * Normalizes a term name for alias comparison: trims whitespace and lower-cases.
     *
     * @param value the raw term name, possibly null
     * @return the normalized term name, or {@code ""} if {@code value} is null
     */
    private static String normalizeTerm(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    /**
     * Normalizes a test/implementation identifier for map-key comparison: trims whitespace and
     * strips a trailing slash.
     *
     * @param value the raw identifier, possibly null or blank
     * @return the normalized identifier, or {@code null} if {@code value} is null or blank
     */
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

    /**
     * Derives the {@code @Provides} fallback lookup key for a test ID: the UUID embedded in the
     * ID, if any, otherwise the ID itself.
     *
     * <p>Version-qualified test IDs (matched via {@code @ProvidesVersion}) commonly embed the
     * test's base UUID; when no version-qualified match is found, falling back to a lookup by
     * that bare UUID against {@code @Provides} lets a test still bind to an implementation that
     * only declares the unversioned identifier.
     *
     * @param testId the policy test's identifier
     * @return the embedded UUID if present, otherwise {@code testId}, or {@code null} if
     *     {@code testId} is null or blank
     */
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

    /**
     * Outcome of candidate selection for a single test, produced by {@link #selectCandidate}.
     *
     * @param candidates every implementation that matched the test's identifier (or the single
     *     explicitly-mapped implementation, if an explicit mapping applied)
     * @param chosen the implementation selected from {@code candidates}, or {@code null} if none
     *     matched
     * @param selectionReason human-readable explanation of why {@code chosen} was selected
     * @param diagnostics diagnostic messages describing how selection proceeded
     * @param ambiguous whether {@code candidates} contained more than one implementation not
     *     forming an expected default/parameterized pair
     */
    private record Selection(
            List<DiscoveredImplementation> candidates,
            DiscoveredImplementation chosen,
            String selectionReason,
            List<String> diagnostics,
            boolean ambiguous) {
    }

    /**
     * Wraps the {@link ImplementationBinding} produced by {@link #evaluateCandidate} for a
     * single test.
     *
     * @param binding the resulting binding, including its parameter bindings and diagnostics
     */
    private record CandidateEvaluation(ImplementationBinding binding) {
    }
}
