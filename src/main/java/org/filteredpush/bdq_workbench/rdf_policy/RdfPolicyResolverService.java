package org.filteredpush.bdq_workbench.rdf_policy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
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
import org.filteredpush.bdq_workbench.model.UseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads bdquc and BDQ RDF definitions and resolves linked tests for use cases. */
public class RdfPolicyResolverService implements PolicyResolverService {
    private static final Logger LOG = LoggerFactory.getLogger(RdfPolicyResolverService.class);
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
            if (label == null) {
                unresolved.add(new TestDefinition(testId, testId, Phase.PRE_AMENDMENT, Map.of()));
            } else {
                resolved.add(new TestDefinition(testId, label, inferPhase(rdf, testId), Map.of()));
            }
        }

        return new ExecutionPlan(
                useCase,
                new Policy(resolution.primaryPolicyId(useCase.policyId()), linkedTestIds),
                resolved,
                unresolved);
    }

    private static PolicyResolution resolveLinkedTests(Model model, UseCase useCase) {
        Set<String> useCaseIds = candidateUseCaseIds(useCase);
        Set<String> matchedPolicyIds = new LinkedHashSet<>();
        Set<String> matchedTestIds = new LinkedHashSet<>();
        Map<String, Set<String>> testsByPolicy = new LinkedHashMap<>();

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
            Set<String> policyTests = new LinkedHashSet<>(collectLinkedTests(statement.getSubject(), model));
            testsByPolicy.putIfAbsent(policyId, policyTests);
            matchedTestIds.addAll(policyTests);
        }

        for (String useCaseId : useCaseIds) {
            matchedTestIds.addAll(collectLinkedTests(model.getResource(useCaseId), model));
        }

        testsByPolicy.forEach((policyId, testIds) -> LOG.debug(
                "Matched policy {} with {} linked tests{}",
                policyId,
                testIds.size(),
                testIds.isEmpty() ? "" : ": " + testIds));
        LOG.debug("Resolved {} policies and {} tests for use case {} (candidates: {})",
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

    private static boolean isTestPredicate(String localName) {
        String normalized = normalizeName(localName);
        return normalized.contains("test")
                || normalized.contains("validation")
                || normalized.contains("measurement")
                || normalized.contains("measure");
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

    private static List<String> collectLinkedTests(Resource subject, Model model) {
        Set<String> testIds = new LinkedHashSet<>();
        if (subject == null) {
            return List.of();
        }
        var properties = model.listStatements(subject, null, (org.apache.jena.rdf.model.RDFNode) null);
        while (properties.hasNext()) {
            var property = properties.nextStatement();
            if (!isTestPredicate(property.getPredicate().getLocalName()) || !property.getObject().isResource()) {
                continue;
            }
            String testId = resourceUri(property.getResource());
            if (!testId.isBlank()) {
                testIds.add(testId);
            }
        }
        return List.copyOf(testIds);
    }

    private static String resolveLabel(Model model, String uri) {
        Resource r = model.getResource(uri);
        if (r == null) {
            return null;
        }
        var stmt = r.getProperty(model.createProperty("http://www.w3.org/2000/01/rdf-schema#label"));
        return stmt == null ? null : stmt.getString();
    }

    private static Phase inferPhase(Model model, String testId) {
        Resource r = model.getResource(testId);
        String local = r.getLocalName() == null ? "" : r.getLocalName().toLowerCase();
        if (local.contains("amend")) {
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
            LOG.info("Loaded RDF definitions file {}: {} policies, {} tests",
                    path.toAbsolutePath(), stats.policyCount(), stats.testCount());
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
        Set<String> policies = new LinkedHashSet<>();
        Set<String> tests = new LinkedHashSet<>();
        Map<String, Integer> testsPerPolicy = new LinkedHashMap<>();

        var statements = model.listStatements();
        while (statements.hasNext()) {
            var statement = statements.nextStatement();
            if (isHasUseCasePredicate(statement.getPredicate().getLocalName()) && statement.getSubject().isResource()) {
                String policyId = resourceUri(statement.getSubject());
                policies.add(policyId);
                int count = collectLinkedTests(statement.getSubject(), model).size();
                testsPerPolicy.put(policyId, count);
                tests.addAll(collectLinkedTests(statement.getSubject(), model));
            }
        }

        if (policies.isEmpty()) {
            policies.addAll(findTypedResources(model, "policy"));
        }
        if (tests.isEmpty()) {
            tests.addAll(findTypedResources(model, "validation", "test", "measure"));
            tests.addAll(findSubClassResources(model, "dataqualityneed", "validation", "test", "measure"));
        }

        if (!testsPerPolicy.isEmpty()) {
            LOG.debug("Policy-to-test counts in current RDF file: {}", testsPerPolicy);
        }
        return new FileStats(policies.size(), tests.size());
    }

    private static Set<String> findTypedResources(Model model, String... typeTokens) {
        Set<String> matches = new HashSet<>();
        String rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
        var statements = model.listStatements(null, model.createProperty(rdfType), (RDFNode) null);
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

    private static Set<String> findSubClassResources(Model model, String... typeTokens) {
        Set<String> matches = new HashSet<>();
        String subClassOf = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
        var statements = model.listStatements(null, model.createProperty(subClassOf), (RDFNode) null);
        while (statements.hasNext()) {
            var statement = statements.nextStatement();
            if (!statement.getSubject().isResource() || !statement.getObject().isResource()) {
                continue;
            }
            String parentId = resourceUri(statement.getResource());
            String normalizedParent = normalizeUriForMatch(parentId);
            for (String token : typeTokens) {
                if (normalizedParent.contains(token.toLowerCase(Locale.ROOT))) {
                    matches.add(resourceUri(statement.getSubject()));
                    break;
                }
            }
        }
        return matches;
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

    private record FileStats(int policyCount, int testCount) {
    }

}
