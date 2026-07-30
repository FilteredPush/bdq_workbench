package org.filteredpush.bdq_workbench.rdf_policy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
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

        List<String> linkedTestIds = resolveLinkedTests(rdf, useCase.policyId());
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
                new Policy(useCase.policyId(), linkedTestIds),
                resolved,
                unresolved);
    }

    private static List<String> resolveLinkedTests(Model model, String policyId) {
        String query = """
                PREFIX bdq: <https://rs.tdwg.org/bdqffdq/terms/>
                SELECT DISTINCT ?test WHERE {
                  VALUES ?policy { <%s> }
                  { ?policy bdq:hasTest ?test . }
                  UNION
                  { ?policy <http://rs.tdwg.org/dwc/terms/hasMeasurement> ?test . }
                }
                """.formatted(policyId);
        var qexec = org.apache.jena.query.QueryExecutionFactory.create(
                org.apache.jena.query.QueryFactory.create(query),
                model);
        List<String> ids = new ArrayList<>();
        try (qexec) {
            qexec.execSelect().forEachRemaining(row -> ids.add(row.getResource("test").getURI()));
        }
        if (!ids.isEmpty()) {
            return ids;
        }
        Resource policy = model.getResource(policyId);
        var iterator = policy.listProperties();
        while (iterator.hasNext()) {
            var statement = iterator.nextStatement();
            if (statement.getPredicate().getLocalName().toLowerCase().contains("test")
                    && statement.getObject().isResource()) {
                ids.add(statement.getResource().getURI());
            }
        }
        return ids;
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
            readIntoModel(path, model);
        }
        return model;
    }

    private static void readIntoModel(Path path, Model model) {
        List<Lang> languages = orderedLangCandidates(path);
        RiotException lastRiot = null;
        IOException lastIo = null;
        for (Lang lang : languages) {
            try (InputStream in = Files.newInputStream(path)) {
                LOG.debug("Parsing RDF definitions file {} as {}", path.toAbsolutePath(), lang.getName());
                RDFParser.source(in)
                        .base(path.toUri().toString())
                        .lang(lang)
                        .parse(model);
                return;
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

}
