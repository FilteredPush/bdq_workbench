/** RdfPolicyResolverService.java
 *
 * PolicyResolverService implementation that loads BDQ/bdquc use case and policy definitions
 * from RDF (RDF/XML, Turtle, or JSON-LD) and follows their links to resolve a use case's tests.
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
package org.filteredpush.bdq_workbench.rdf_policy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Policy;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PolicyResolverService} that resolves a use case identifier against BDQ Use Cases
 * (bdquc) and BDQ RDF/OWL definitions using Apache Jena.
 *
 * <p>The use case itself is loaded from a single XML or RDF source (via
 * {@link UseCaseXmlParser#loadUseCases(Path)}), while the tests a use case's policy requires are
 * discovered by loading one or more RDF definition files (RDF/XML, Turtle, or JSON-LD — see
 * {@link #loadRdf(List)}) into a single Jena {@link Model} and walking that model's statements.
 *
 * <p>Resolution does not rely on any single fixed vocabulary. Instead it builds, for each RDF
 * file, a {@link SemanticIndex} that discovers which classes in that file are (or subclass)
 * a small set of expected "core" Data Quality Need classes — {@code DataQualityNeed},
 * {@code Validation}, {@code Amendment}, {@code Measure}, and {@code Issue} — by local name, then
 * treats any resource typed with one of those classes, or any resource reached through a
 * predicate whose {@code rdfs:range} is one of those classes, as a candidate test. A policy is
 * matched to the requested use case via a predicate whose local name normalizes to
 * {@code hasUseCase} pointing at the use case's URI (or an {@code http}/{@code https} or
 * versioned/unversioned equivalent of it — see {@link #candidateUseCaseIds(UseCase)}); the tests
 * linked from that policy (and, as a fallback, tests linked directly from the use case resource
 * itself) are then collected by scanning that policy's properties for objects that are resources
 * and are either linked via a predicate that looks like a test-link predicate or are themselves
 * classified as test resources by the semantic index (see {@link #collectLinkedTests}). Each
 * linked test is labeled from its {@code rdfs:label} where present (tests without a label are
 * reported as unresolved rather than dropped), typed by matching {@code Amendment}/
 * {@code Measure}/{@code Issue}/{@code Validation} tokens in its local name or {@code rdf:type}
 * values, and assigned to the {@code AMENDMENT} phase if its type is {@code AMENDMENT} or
 * otherwise to {@code PRE_AMENDMENT}.
 *
 * <p>Instances are immutable and safe to reuse across calls to {@link #resolve(String)}, though
 * every call re-reads and re-parses the configured files.
 */
public class RdfPolicyResolverService implements PolicyResolverService {
    private static final Logger LOG = LoggerFactory.getLogger(RdfPolicyResolverService.class);
    private static final String RDF_TYPE_URI = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String RDFS_SUBCLASS_OF_URI = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String RDFS_RANGE_URI = "http://www.w3.org/2000/01/rdf-schema#range";
    private static final String TEST_METADATA_EXPECTED_RESPONSE = "expectedResponse";
    private static final String TEST_METADATA_NOTE = "note";
    private final Path useCaseXmlPath;
    private final List<Path> rdfFiles;

    /**
     * Creates a resolver that loads use cases from {@code useCaseXmlPath} and test/policy
     * definitions from {@code rdfFiles}.
     *
     * @param useCaseXmlPath path to the use case source, parsed via
     *     {@link UseCaseXmlParser#loadUseCases(Path)}; read afresh on every call to
     *     {@link #resolve(String)}
     * @param rdfFiles RDF/OWL definition files (RDF/XML, Turtle, or JSON-LD) containing the
     *     policy-to-test and use-case-to-policy links; missing files are silently skipped, and
     *     the list is defensively copied
     */
    public RdfPolicyResolverService(Path useCaseXmlPath, List<Path> rdfFiles) {
        this.useCaseXmlPath = useCaseXmlPath;
        this.rdfFiles = List.copyOf(rdfFiles);
    }

    /**
     * Resolves {@code selectedUseCaseId} into an {@link ExecutionPlan}.
     *
     * <p>Loads the use case from {@link #useCaseXmlPath}, selects the matching {@link UseCase}
     * (see {@link #selectUseCase}), loads and merges all configured RDF definition files into a
     * single model, and follows the RDF links from that use case's policy to its tests (see
     * {@link #resolveLinkedTests}). Each linked test ID is then labeled, typed, and assigned
     * metadata (see {@link #resolveLabel}, {@link #inferTestType}, {@link #resolveTestMetadata});
     * tests with no resolvable {@code rdfs:label} are placed in the plan's unresolved list rather
     * than the resolved one.
     *
     * @param selectedUseCaseId identifier or URI of the use case to resolve; if null, blank, or
     *     not found, {@link #selectUseCase} falls back to a URI-equivalence match and then to an
     *     arbitrary loaded use case
     * @return the execution plan containing the resolved use case, its policy (with the full list
     *     of linked test IDs), and the tests that were and were not successfully resolved
     * @throws org.filteredpush.bdq_workbench.app.AppException if no use cases can be loaded, or
     *     an RDF definition file cannot be read or parsed
     */
    @Override
    public ExecutionPlan resolve(String selectedUseCaseId) {
        LOG.debug("Resolving policy from use case source: {}", useCaseXmlPath.toAbsolutePath());
        Map<String, UseCase> useCases = UseCaseXmlParser.loadUseCases(useCaseXmlPath);
        UseCase useCase = selectUseCase(useCases, selectedUseCaseId);
        Model rdf = loadRdf(rdfFiles);

        PolicyResolution resolution = resolveLinkedTests(rdf, useCase);
        List<String> linkedTestIds = resolution.testIds();
        List<TestDefinition> resolved = new ArrayList<>();
        List<TestDefinition> unresolved = new ArrayList<>();
        for (String testId : linkedTestIds) {
            String label = resolveLabel(rdf, testId);
            TestType testType = inferTestType(rdf, testId);
            Map<String, String> metadata = resolveTestMetadata(rdf, testId);
            if (label == null) {
                unresolved.add(new TestDefinition(testId, testId, testType, inferPhase(testType), Map.of(), metadata));
            } else {
                resolved.add(new TestDefinition(testId, label, testType, inferPhase(testType), Map.of(), metadata));
            }
        }

        return new ExecutionPlan(
                useCase,
                new Policy(resolution.primaryPolicyId(useCase.policyId()), linkedTestIds),
                resolved,
                unresolved);
    }

    /**
     * Parses each of the given RDF definition files independently and reports how many distinct
     * use cases, policies, and tests each one contributes, for diagnostic/preview purposes (for
     * example, reporting to a user what a configured set of definition files actually contains)
     * rather than for resolving a specific use case.
     *
     * <p>Unlike {@link #resolve(String)}, files are parsed one at a time (not merged into a
     * single model), so counts reflect only the links present within each individual file.
     * Nonexistent paths are skipped rather than causing a failure.
     *
     * @param paths RDF definition files to summarize
     * @return a summary with per-file counts and totals de-duplicated by ID across all files
     */
    public static RdfDefinitionSummary summarizeDefinitionSources(List<Path> paths) {
        List<RdfDefinitionFileSummary> files = new ArrayList<>();
        Set<String> allUseCases = new LinkedHashSet<>();
        Set<String> allPolicies = new LinkedHashSet<>();
        Set<String> allTests = new LinkedHashSet<>();

        for (Path path : paths) {
            if (!Files.exists(path)) {
                continue;
            }
            Model parsed = readIntoModel(path);
            FileStats stats = collectFileStats(parsed);
            files.add(new RdfDefinitionFileSummary(
                    path,
                    stats.useCaseCount(),
                    stats.policyCount(),
                    stats.testCount()));
            allUseCases.addAll(stats.useCaseIds());
            allPolicies.addAll(stats.policyIds());
            allTests.addAll(stats.testIds());
        }
        return new RdfDefinitionSummary(
                List.copyOf(files),
                allUseCases.size(),
                allPolicies.size(),
                allTests.size());
    }

    private static PolicyResolution resolveLinkedTests(Model model, UseCase useCase) {
        Set<String> useCaseIds = candidateUseCaseIds(useCase);
        Set<String> matchedPolicyIds = new LinkedHashSet<>();
        Set<String> matchedTestIds = new LinkedHashSet<>();
        Map<String, PolicyTestExtraction> extractionByPolicy = new LinkedHashMap<>();
        SemanticIndex semanticIndex = buildSemanticIndex(model);
        Set<String> globalTestCandidates = semanticIndex.testResourceIds();

        var statements = model.listStatements();
        while (statements.hasNext()) {
            var statement = statements.nextStatement();
            if (!isHasUseCasePredicate(statement.getPredicate().getLocalName())) {
                continue;
            }
            if (!statement.getObject().isResource()) {
                continue;
            }
            String linkedUseCaseId = resourceUri(statement.getResource());
            if (!matchesAnyUri(linkedUseCaseId, useCaseIds)) {
                continue;
            }
            String policyId = resourceUri(statement.getSubject());
            matchedPolicyIds.add(policyId);
            PolicyTestExtraction extraction = collectLinkedTests(statement.getSubject(), model, semanticIndex);
            extractionByPolicy.putIfAbsent(policyId, extraction);
            matchedTestIds.addAll(extraction.testIds());
        }

        for (String useCaseId : useCaseIds) {
            matchedTestIds.addAll(collectLinkedTests(model.getResource(useCaseId), model, semanticIndex).testIds());
        }

        extractionByPolicy.forEach((policyId, extraction) -> LOG.info(
                "Matched policy {} with {} linked tests{} using predicates {}",
                policyId,
                extraction.testIds().size(),
                extraction.testIds().isEmpty() ? "" : ": " + extraction.testIds(),
                extraction.predicates().isEmpty() ? "[]" : extraction.predicates()));
        if (matchedPolicyIds.isEmpty()) {
            LOG.warn("No policies matched selected use case {} (candidates: {})", useCase.id(), useCaseIds);
        } else if (matchedTestIds.isEmpty()) {
            LOG.warn(
                    "Policies matched for use case {}, but no tests were linked. Candidate tests seen in RDF: {}",
                    useCase.id(),
                    globalTestCandidates.size());
        }
        LOG.info("Resolved {} policies and {} tests for use case {} (candidates: {})",
                matchedPolicyIds.size(), matchedTestIds.size(), useCase.id(), useCaseIds);
        return new PolicyResolution(List.copyOf(matchedPolicyIds), List.copyOf(matchedTestIds));
    }

    private static Set<String> candidateUseCaseIds(UseCase useCase) {
        Set<String> candidates = new LinkedHashSet<>();
        addEquivalentUriCandidates(candidates, useCase.id());
        addEquivalentUriCandidates(candidates, useCase.policyId());
        return candidates;
    }

    private static void addEquivalentUriCandidates(Set<String> candidates, String uri) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        String trimmed = uri.trim();
        candidates.add(trimmed);
        if (trimmed.startsWith("https://")) {
            candidates.add("http://" + trimmed.substring("https://".length()));
        } else if (trimmed.startsWith("http://")) {
            candidates.add("https://" + trimmed.substring("http://".length()));
        }

        String withoutVersionDate = trimmed.replaceFirst("(/terms/version/[^/]+)-\\d{4}-\\d{2}-\\d{2}$", "$1");
        String unversioned = withoutVersionDate.replace("/terms/version/", "/terms/");
        if (!unversioned.equals(trimmed)) {
            candidates.add(unversioned);
            if (unversioned.startsWith("https://")) {
                candidates.add("http://" + unversioned.substring("https://".length()));
            } else if (unversioned.startsWith("http://")) {
                candidates.add("https://" + unversioned.substring("http://".length()));
            }
        }
    }

    private static boolean matchesAnyUri(String candidate, Set<String> targets) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedCandidate = normalizeUriForMatch(candidate);
        for (String target : targets) {
            if (candidate.equals(target) || normalizedCandidate.equals(normalizeUriForMatch(target))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeUriForMatch(String uri) {
        String normalized = uri.trim();
        if (normalized.startsWith("https://")) {
            normalized = normalized.substring("https://".length());
        } else if (normalized.startsWith("http://")) {
            normalized = normalized.substring("http://".length());
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isHasUseCasePredicate(String localName) {
        return normalizeName(localName).equals("hasusecase");
    }

    private static boolean isLikelyNeedPredicate(String localName) {
        String normalized = normalizeName(localName);
        return normalized.contains("validation")
                || normalized.contains("amendment")
                || normalized.contains("measurement")
                || normalized.contains("measure")
                || normalized.contains("issue")
                || normalized.contains("dataqualityneed")
                || normalized.endsWith("need");
    }

    private static String normalizeName(String localName) {
        return localName == null ? "" : localName.toLowerCase(Locale.ROOT);
    }

    private static String resourceUri(Resource resource) {
        if (resource == null) {
            return "";
        }
        return resource.getURI() == null ? resource.toString() : resource.getURI();
    }

    private static PolicyTestExtraction collectLinkedTests(Resource subject, Model model, SemanticIndex semanticIndex) {
        Set<String> testIds = new LinkedHashSet<>();
        Set<String> predicateNames = new LinkedHashSet<>();
        if (subject == null) {
            return new PolicyTestExtraction(List.of(), List.of());
        }
        var properties = model.listStatements(subject, null, (org.apache.jena.rdf.model.RDFNode) null);
        while (properties.hasNext()) {
            var property = properties.nextStatement();
            String predicateName = property.getPredicate().getLocalName();
            if (!property.getObject().isResource()) {
                continue;
            }
            String objectId = resourceUri(property.getResource());
            boolean predicateLooksLikeTest = semanticIndex.isTestLinkPredicate(property.getPredicate().getURI(), predicateName);
            boolean objectLooksLikeTest = semanticIndex.isTestResource(objectId);
            if (!predicateLooksLikeTest && !objectLooksLikeTest) {
                continue;
            }
            predicateNames.add(predicateName == null ? property.getPredicate().getURI() : predicateName);
            String testId = resourceUri(property.getResource());
            if (!testId.isBlank()) {
                testIds.add(testId);
            }
        }
        return new PolicyTestExtraction(List.copyOf(testIds), List.copyOf(predicateNames));
    }

    private static String resolveLabel(Model model, String uri) {
        Resource r = model.getResource(uri);
        if (r == null) {
            return null;
        }
        var stmt = r.getProperty(model.createProperty("http://www.w3.org/2000/01/rdf-schema#label"));
        return stmt == null ? null : stmt.getString();
    }

    private static Map<String, String> resolveTestMetadata(Model model, String uri) {
        Resource resource = model.getResource(uri);
        if (resource == null) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, TEST_METADATA_EXPECTED_RESPONSE,
                resolveLiteralProperty(resource, Set.of("hasExpectedResponse", "expectedResponse")));
        putIfPresent(metadata, TEST_METADATA_NOTE,
                resolveLiteralProperty(resource, Set.of("note")));
        return Map.copyOf(metadata);
    }

    private static void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private static String resolveLiteralProperty(Resource resource, Set<String> candidateLocalNames) {
        var statements = resource.listProperties();
        while (statements.hasNext()) {
            var statement = statements.nextStatement();
            String localName = statement.getPredicate().getLocalName();
            if (localName == null || !candidateLocalNames.contains(localName)) {
                continue;
            }
            if (statement.getObject().isLiteral()) {
                return statement.getString();
            }
        }
        return null;
    }

    private static TestType inferTestType(Model model, String testId) {
        Resource r = model.getResource(testId);
        String local = r.getLocalName() == null ? "" : r.getLocalName().toLowerCase();
        if (local.contains("amend")) {
            return TestType.AMENDMENT;
        }
        if (local.contains("measure")) {
            return TestType.MEASURE;
        }
        if (local.contains("issue")) {
            return TestType.ISSUE;
        }
        if (local.contains("validation")) {
            return TestType.VALIDATION;
        }
        var types = model.listStatements(r, model.createProperty(RDF_TYPE_URI), (RDFNode) null);
        while (types.hasNext()) {
            String typeUri = resourceUri(types.nextStatement().getResource()).toLowerCase();
            if (typeUri.endsWith("amendment")) {
                return TestType.AMENDMENT;
            }
            if (typeUri.endsWith("measure")) {
                return TestType.MEASURE;
            }
            if (typeUri.endsWith("issue")) {
                return TestType.ISSUE;
            }
            if (typeUri.endsWith("validation")) {
                return TestType.VALIDATION;
            }
        }
        return TestType.UNKNOWN;
    }

    private static Phase inferPhase(TestType testType) {
        if (testType == TestType.AMENDMENT) {
            return Phase.AMENDMENT;
        }
        return Phase.PRE_AMENDMENT;
    }

    private static UseCase selectUseCase(Map<String, UseCase> useCases, String selectedUseCaseId) {
        if (selectedUseCaseId != null && !selectedUseCaseId.isBlank() && useCases.containsKey(selectedUseCaseId)) {
            return useCases.get(selectedUseCaseId);
        }
        if (selectedUseCaseId != null && !selectedUseCaseId.isBlank()) {
            return useCases.values().stream()
                    .filter(useCase -> matchesAnyUri(selectedUseCaseId, candidateUseCaseIds(useCase)))
                    .findFirst()
                    .orElseGet(() -> useCases.values().stream().findFirst().orElseThrow(() -> new AppException("No use cases found")));
        }
        return useCases.values().stream().findFirst().orElseThrow(() -> new AppException("No use cases found"));
    }

    private static Model loadRdf(List<Path> paths) {
        Model model = ModelFactory.createDefaultModel();
        for (Path path : paths) {
            if (!Files.exists(path)) {
                LOG.debug("Skipping missing RDF definitions file: {}", path);
                continue;
            }
            LOG.debug("Loading RDF definitions from {}", path.toAbsolutePath());
            Model parsed = readIntoModel(path);
            FileStats stats = collectFileStats(parsed);
            LOG.info("Loaded RDF definitions file {}: {} use cases, {} policies, {} tests",
                    path.toAbsolutePath(), stats.useCaseCount(), stats.policyCount(), stats.testCount());
            model.add(parsed);
        }
        return model;
    }

    private static Model readIntoModel(Path path) {
        List<Lang> languages = orderedLangCandidates(path);
        RiotException lastRiot = null;
        IOException lastIo = null;
        for (Lang lang : languages) {
            try (InputStream in = Files.newInputStream(path)) {
                LOG.debug("Parsing RDF definitions file {} as {}", path.toAbsolutePath(), lang.getName());
                Model model = ModelFactory.createDefaultModel();
                RDFParser.source(in)
                        .base(path.toUri().toString())
                        .lang(lang)
                        .parse(model);
                return model;
            } catch (RiotException e) {
                lastRiot = e;
            } catch (IOException e) {
                lastIo = e;
            }
        }
        if (lastIo != null) {
            throw new AppException("Unable to read RDF definitions from " + path, lastIo);
        }
        throw new AppException("Unable to parse RDF definitions from " + path, lastRiot);
    }

    private static FileStats collectFileStats(Model model) {
        Set<String> useCases = new LinkedHashSet<>();
        Set<String> policies = new LinkedHashSet<>();
        Set<String> tests = new LinkedHashSet<>();
        Map<String, Integer> testsPerPolicy = new LinkedHashMap<>();
        SemanticIndex semanticIndex = buildSemanticIndex(model);

        var statements = model.listStatements();
        while (statements.hasNext()) {
            var statement = statements.nextStatement();
            if (isHasUseCasePredicate(statement.getPredicate().getLocalName()) && statement.getSubject().isResource()) {
                String policyId = resourceUri(statement.getSubject());
                policies.add(policyId);
                if (statement.getObject().isResource()) {
                    useCases.add(resourceUri(statement.getResource()));
                }
                int count = collectLinkedTests(statement.getSubject(), model, semanticIndex).testIds().size();
                testsPerPolicy.put(policyId, count);
                tests.addAll(collectLinkedTests(statement.getSubject(), model, semanticIndex).testIds());
            }
        }

        if (useCases.isEmpty()) {
            useCases.addAll(findTypedResources(model, "usecase"));
        }
        if (policies.isEmpty()) {
            policies.addAll(findTypedResources(model, "policy"));
        }
        if (tests.isEmpty()) {
            tests.addAll(semanticIndex.testResourceIds());
        }

        if (!testsPerPolicy.isEmpty()) {
            LOG.debug("Policy-to-test counts in current RDF file: {}", testsPerPolicy);
        }
        return new FileStats(
                useCases.size(),
                policies.size(),
                tests.size(),
                Set.copyOf(useCases),
                Set.copyOf(policies),
                Set.copyOf(tests));
    }

    private static Set<String> findTypedResources(Model model, String... typeTokens) {
        Set<String> matches = new HashSet<>();
        var statements = model.listStatements(null, model.createProperty(RDF_TYPE_URI), (RDFNode) null);
        while (statements.hasNext()) {
            var statement = statements.nextStatement();
            if (!statement.getSubject().isResource() || !statement.getObject().isResource()) {
                continue;
            }
            String typeId = resourceUri(statement.getResource());
            String normalizedType = normalizeUriForMatch(typeId);
            for (String token : typeTokens) {
                if (normalizedType.contains(token.toLowerCase(Locale.ROOT))) {
                    matches.add(resourceUri(statement.getSubject()));
                    break;
                }
            }
        }
        return matches;
    }

    private static SemanticIndex buildSemanticIndex(Model model) {
        Map<String, Set<String>> parentsByClass = new HashMap<>();
        Set<String> allResources = new HashSet<>();
        var subclasses = model.listStatements(null, model.createProperty(RDFS_SUBCLASS_OF_URI), (RDFNode) null);
        while (subclasses.hasNext()) {
            var statement = subclasses.nextStatement();
            if (!statement.getSubject().isResource() || !statement.getObject().isResource()) {
                continue;
            }
            String child = resourceUri(statement.getSubject());
            String parent = resourceUri(statement.getResource());
            if (child.isBlank() || parent.isBlank()) {
                continue;
            }
            parentsByClass.computeIfAbsent(normalizeUriForMatch(child), ignored -> new LinkedHashSet<>())
                    .add(normalizeUriForMatch(parent));
            allResources.add(normalizeUriForMatch(child));
            allResources.add(normalizeUriForMatch(parent));
        }

        var allStatements = model.listStatements();
        while (allStatements.hasNext()) {
            var statement = allStatements.nextStatement();
            if (statement.getSubject().isResource()) {
                String subjectId = resourceUri(statement.getSubject());
                if (!subjectId.isBlank()) {
                    allResources.add(normalizeUriForMatch(subjectId));
                }
            }
            if (statement.getObject().isResource()) {
                String objectId = resourceUri(statement.getResource());
                if (!objectId.isBlank()) {
                    allResources.add(normalizeUriForMatch(objectId));
                }
            }
        }

        Set<String> needClasses = new LinkedHashSet<>();
        for (String resourceId : allResources) {
            if (isCoreDataQualityNeedClass(resourceId)) {
                needClasses.add(resourceId);
            }
        }
        Set<String> testTypeClasses = new LinkedHashSet<>();
        for (String resourceId : allResources) {
            if (isClassOrSubclassOf(resourceId, needClasses, parentsByClass, new HashSet<>())) {
                testTypeClasses.add(resourceId);
            }
        }

        Set<String> testResources = new LinkedHashSet<>();
        var typedStatements = model.listStatements(null, model.createProperty(RDF_TYPE_URI), (RDFNode) null);
        while (typedStatements.hasNext()) {
            var statement = typedStatements.nextStatement();
            if (!statement.getSubject().isResource() || !statement.getObject().isResource()) {
                continue;
            }
            String subjectId = resourceUri(statement.getSubject());
            String typeId = resourceUri(statement.getResource());
            if (subjectId.isBlank() || typeId.isBlank()) {
                continue;
            }
            if (testTypeClasses.contains(normalizeUriForMatch(typeId))) {
                testResources.add(subjectId);
            }
        }

        Set<String> testLinkPredicates = new LinkedHashSet<>();
        var rangeStatements = model.listStatements(null, model.createProperty(RDFS_RANGE_URI), (RDFNode) null);
        while (rangeStatements.hasNext()) {
            var statement = rangeStatements.nextStatement();
            if (!statement.getSubject().isResource() || !statement.getObject().isResource()) {
                continue;
            }
            String predicateUri = resourceUri(statement.getSubject());
            String rangeUri = resourceUri(statement.getResource());
            if (predicateUri.isBlank() || rangeUri.isBlank()) {
                continue;
            }
            if (testTypeClasses.contains(normalizeUriForMatch(rangeUri))) {
                testLinkPredicates.add(normalizeUriForMatch(predicateUri));
            }
        }

        LOG.debug(
                "Semantic RDF index: {} DataQualityNeed class candidates, {} DataQualityNeed-derived classes, {} test resources, {} test-link predicates",
                needClasses.size(),
                testTypeClasses.size(),
                testResources.size(),
                testLinkPredicates.size());
        return new SemanticIndex(testResources, testLinkPredicates, testTypeClasses);
    }

    private static boolean isCoreDataQualityNeedClass(String resourceId) {
        String normalized = normalizeUriForMatch(resourceId);
        int slash = normalized.lastIndexOf('/');
        int hash = normalized.lastIndexOf('#');
        int cut = Math.max(slash, hash);
        String local = (cut >= 0 ? normalized.substring(cut + 1) : normalized).toLowerCase(Locale.ROOT);
        return local.equals("dataqualityneed")
                || local.equals("validation")
                || local.equals("amendment")
                || local.equals("measure")
                || local.equals("issue");
    }

    private static boolean isClassOrSubclassOf(
            String classId,
            Set<String> rootClasses,
            Map<String, Set<String>> parentsByClass,
            Set<String> visiting) {
        if (rootClasses.contains(classId)) {
            return true;
        }
        if (!visiting.add(classId)) {
            return false;
        }
        for (String parent : parentsByClass.getOrDefault(classId, Set.of())) {
            if (rootClasses.contains(parent) || isClassOrSubclassOf(parent, rootClasses, parentsByClass, visiting)) {
                return true;
            }
        }
        return false;
    }

    private static List<Lang> orderedLangCandidates(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xml") || name.endsWith(".rdf") || name.endsWith(".owl")) {
            return List.of(Lang.RDFXML, Lang.TURTLE, Lang.JSONLD);
        }
        if (name.endsWith(".ttl")) {
            return List.of(Lang.TURTLE, Lang.RDFXML, Lang.JSONLD);
        }
        if (name.endsWith(".jsonld") || name.endsWith(".json")) {
            return List.of(Lang.JSONLD, Lang.TURTLE, Lang.RDFXML);
        }
        return List.of(Lang.TURTLE, Lang.RDFXML, Lang.JSONLD);
    }

    private record PolicyResolution(List<String> policyIds, List<String> testIds) {
        private String primaryPolicyId(String fallback) {
            return policyIds.isEmpty() ? fallback : policyIds.get(0);
        }
    }

    private record PolicyTestExtraction(List<String> testIds, List<String> predicates) {
    }

    private record SemanticIndex(
            Set<String> testResourceIds,
            Set<String> testLinkPredicates,
            Set<String> testTypeClasses) {
        private boolean isTestResource(String uri) {
            return testResourceIds.contains(uri)
                    || testResourceIds.contains(normalizeUriForMatch(uri))
                    || testTypeClasses.contains(normalizeUriForMatch(uri));
        }

        private boolean isTestLinkPredicate(String uri, String localName) {
            String normalizedUri = normalizeUriForMatch(uri);
            return testLinkPredicates.contains(uri)
                    || testLinkPredicates.contains(normalizedUri)
                    || isLikelyNeedPredicate(localName);
        }
    }

    private record FileStats(
            int useCaseCount,
            int policyCount,
            int testCount,
            Set<String> useCaseIds,
            Set<String> policyIds,
            Set<String> testIds) {
    }

    /**
     * Aggregated result of {@link #summarizeDefinitionSources(List)} across a set of RDF
     * definition files.
     *
     * @param files per-file summaries, in the order the input paths were given
     * @param totalUseCases number of distinct use case IDs found across all files
     * @param totalPolicies number of distinct policy IDs found across all files
     * @param totalTests number of distinct test IDs found across all files
     */
    public record RdfDefinitionSummary(
            List<RdfDefinitionFileSummary> files,
            int totalUseCases,
            int totalPolicies,
            int totalTests) {
    }

    /**
     * Summary of the use cases, policies, and tests found in a single RDF definition file.
     *
     * @param path the file that was parsed
     * @param useCaseCount number of distinct use case IDs found in this file
     * @param policyCount number of distinct policy IDs found in this file
     * @param testCount number of distinct test IDs linked from a policy in this file (or, if no
     *     policy-to-test links were found, the number of resources classified as tests by the
     *     file's semantic index)
     */
    public record RdfDefinitionFileSummary(
            Path path,
            int useCaseCount,
            int policyCount,
            int testCount) {
    }

}
