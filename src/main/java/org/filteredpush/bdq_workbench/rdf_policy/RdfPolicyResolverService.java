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

/** Loads bdquc and BDQ RDF definitions and resolves linked tests for use cases. */
public class RdfPolicyResolverService implements PolicyResolverService {
    private static final Logger LOG = LoggerFactory.getLogger(RdfPolicyResolverService.class);
    private static final String RDF_TYPE_URI = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String RDFS_SUBCLASS_OF_URI = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String RDFS_RANGE_URI = "http://www.w3.org/2000/01/rdf-schema#range";
    private static final String TEST_METADATA_EXPECTED_RESPONSE = "expectedResponse";
    private static final String TEST_METADATA_NOTE = "note";
    private final Path useCaseXmlPath;
    private final List<Path> rdfFiles;

    public RdfPolicyResolverService(Path useCaseXmlPath, List<Path> rdfFiles) {
        this.useCaseXmlPath = useCaseXmlPath;
        this.rdfFiles = List.copyOf(rdfFiles);
    }

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

    public record RdfDefinitionSummary(
            List<RdfDefinitionFileSummary> files,
            int totalUseCases,
            int totalPolicies,
            int totalTests) {
    }

    public record RdfDefinitionFileSummary(
            Path path,
            int useCaseCount,
            int policyCount,
            int testCount) {
    }

}
