/** TestResultsSummaryService.java
 *
 * Queries a test's ratified definition (bdqtest.ttl) together with a run's RDF results to
 * produce a human-readable summary of what a test does and how it performed on the input data.
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
package org.filteredpush.bdq_workbench.reporting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.rdf_policy.RdfDefinitionsLoader;

/**
 * Produces a human-readable summary of a test, by querying its ratified definition together with
 * a run's RDF results.
 *
 * <p>Loads the given RDF sources (typically the run's {@code rdfDefinitions} — e.g.
 * {@code bdqtest.ttl}/{@code bdqffdq.owl} — plus the use case source and the run's exported
 * {@code bdq-report-rdf.ttl}) into one merged model via {@link RdfDefinitionsLoader#load(List)},
 * then, for a given test, reports what the test does (label, description, dimension, criterion or
 * enhancement, and the information elements it acts upon/consults, from the ratified definition)
 * and how it performed (counts of distinct status/result/comment combinations, and the most
 * frequent observed values of each acted-upon/consulted information element, both broken out by
 * execution phase since the same test can be bound and run in more than one phase).
 */
public class TestResultsSummaryService {
    private static final String BDQFFDQ = "https://rs.tdwg.org/bdqffdq/terms/";
    private static final String DWC = "http://rs.tdwg.org/dwc/terms/";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String BDQWB_PHASE = "https://github.com/FilteredPush/bdq_workbench/terms/phase";
    private static final int TOP_N = 10;

    private static final String CHAIN_QUERY = """
            PREFIX bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/>
            SELECT ?response ?status ?result ?resultValue ?comment ?phase ?record WHERE {
              ?implementation bdqffdq:producesResponse ?response ;
                              bdqffdq:usesSpecification ?specification .
              ?method bdqffdq:hasSpecification ?specification ;
                      ?forProperty ?given .
              FILTER(?forProperty IN (bdqffdq:forValidation, bdqffdq:forIssue, bdqffdq:forMeasure, bdqffdq:forAmendment))
              OPTIONAL { ?response bdqffdq:hasResponseStatus ?status }
              OPTIONAL { ?response bdqffdq:hasResponseResult ?result }
              OPTIONAL { ?response bdqffdq:hasResponseResultValue ?resultValue }
              OPTIONAL { ?response bdqffdq:hasResponseComment ?comment }
              OPTIONAL { ?response <https://github.com/FilteredPush/bdq_workbench/terms/phase> ?phase }
              OPTIONAL { ?response bdqffdq:appliesTo ?record }
            }
            """;

    private final Model model;

    /**
     * Creates a service over the merged content of {@code rdfSources}.
     *
     * @param rdfSources RDF/OWL files to load and merge — typically a run's
     *     {@link org.filteredpush.bdq_workbench.app.AppConfig#rdfDefinitions()}, its use case
     *     source, and its exported results Turtle
     */
    public TestResultsSummaryService(List<Path> rdfSources) {
        this.model = RdfDefinitionsLoader.load(rdfSources);
    }

    /**
     * Summarizes one test: what it does (from its ratified definition) and how it performed on
     * this run's input data (from the run's RDF results), broken out by execution phase.
     *
     * @param testId the test's IRI, as found on {@code BindingReview.test().id()}
     * @param testType the test's type
     * @return a human-readable summary; notes when no matching responses were found
     */
    public String summarize(String testId, TestType testType) {
        Set<Resource> variants = resolveVariants(testId);
        TestMetadata metadata = fetchTestMetadata(variants);
        List<String> actedUpon = fetchInformationElementTerms(variants, "hasActedUponInformationElement");
        List<String> consulted = fetchInformationElementTerms(variants, "hasConsultedInformationElement");
        List<ResponseRow> rows = fetchResponseRows(variants);

        StringBuilder builder = new StringBuilder();
        builder.append("Test: ").append(testId).append('\n');
        builder.append("Label: ").append(defaulted(metadata.label())).append('\n');
        builder.append("Type: ").append(testType).append('\n');
        if (metadata.description() != null) {
            builder.append("Description: ").append(metadata.description()).append('\n');
        }
        builder.append("Dimension: ").append(defaulted(localName(metadata.dimension()))).append('\n');
        if (metadata.criterion() != null) {
            builder.append("Criterion: ").append(localName(metadata.criterion())).append('\n');
        }
        if (metadata.enhancement() != null) {
            builder.append("Enhancement: ").append(localName(metadata.enhancement())).append('\n');
        }
        builder.append("Acts upon: ").append(actedUpon.isEmpty() ? "(none found)" : joinAsDwcTerms(actedUpon)).append('\n');
        if (!consulted.isEmpty()) {
            builder.append("Consults: ").append(joinAsDwcTerms(consulted)).append('\n');
        }
        builder.append('\n');

        if (rows.isEmpty()) {
            builder.append("No responses found for this test in the run's RDF results.\n");
            return builder.toString();
        }

        Map<String, List<ResponseRow>> rowsByPhase = new LinkedHashMap<>();
        for (ResponseRow row : rows) {
            rowsByPhase.computeIfAbsent(defaulted(row.phase()), key -> new ArrayList<>()).add(row);
        }
        List<String> informationElements = new ArrayList<>();
        informationElements.addAll(actedUpon);
        consulted.stream().filter(term -> !informationElements.contains(term)).forEach(informationElements::add);

        rowsByPhase.forEach((phase, phaseRows) -> {
            builder.append("Phase: ").append(phase).append(" (").append(phaseRows.size()).append(" responses)\n");
            appendTopCounts(builder, "  Response status + result + comment", tallyOutcomes(phaseRows));
            for (String term : informationElements) {
                appendTopCounts(builder, "  dwc:" + term + " values", tallyTermValues(phaseRows, term));
            }
            builder.append('\n');
        });

        return builder.toString();
    }

    private Set<Resource> resolveVariants(String testId) {
        Resource given = model.createResource(testId);
        Property isVersionOf = model.createProperty(DCTERMS, "isVersionOf");
        Set<Resource> variants = new LinkedHashSet<>();
        variants.add(given);
        StmtIterator versionOf = given.listProperties(isVersionOf);
        while (versionOf.hasNext()) {
            RDFNode object = versionOf.nextStatement().getObject();
            if (object.isResource()) {
                variants.add(object.asResource());
            }
        }
        StmtIterator versionedBy = model.listStatements(null, isVersionOf, given);
        while (versionedBy.hasNext()) {
            variants.add(versionedBy.nextStatement().getSubject());
        }
        return variants;
    }

    private TestMetadata fetchTestMetadata(Set<Resource> variants) {
        String label = null;
        String description = null;
        String dimension = null;
        String criterion = null;
        String enhancement = null;
        for (Resource variant : variants) {
            label = label != null ? label : literalValue(variant, RDFS_LABEL);
            description = description != null ? description : literalValue(variant, DCTERMS + "description");
            dimension = dimension != null ? dimension : resourceUri(variant, BDQFFDQ + "hasDataQualityDimension");
            criterion = criterion != null ? criterion : resourceUri(variant, BDQFFDQ + "hasCriterion");
            enhancement = enhancement != null ? enhancement : resourceUri(variant, BDQFFDQ + "hasEnhancement");
        }
        return new TestMetadata(label, description, dimension, criterion, enhancement);
    }

    private List<String> fetchInformationElementTerms(Set<Resource> variants, String propertyLocalName) {
        Set<String> terms = new LinkedHashSet<>();
        Property property = model.createProperty(BDQFFDQ, propertyLocalName);
        Property composedOf = model.createProperty(BDQFFDQ, "composedOf");
        for (Resource variant : variants) {
            StmtIterator elements = variant.listProperties(property);
            while (elements.hasNext()) {
                RDFNode element = elements.nextStatement().getObject();
                if (!element.isResource()) {
                    continue;
                }
                StmtIterator composed = element.asResource().listProperties(composedOf);
                while (composed.hasNext()) {
                    RDFNode value = composed.nextStatement().getObject();
                    String uri = value.isResource() ? value.asResource().getURI() : null;
                    terms.add(uri != null && uri.startsWith(DWC) ? localName(uri) : value.toString());
                }
            }
        }
        return List.copyOf(terms);
    }

    private List<ResponseRow> fetchResponseRows(Set<Resource> variants) {
        List<ResponseRow> rows = new ArrayList<>();
        Query query = QueryFactory.create(CHAIN_QUERY);
        for (Resource variant : variants) {
            QuerySolutionMap initialBinding = new QuerySolutionMap();
            initialBinding.add("given", variant);
            try (QueryExecution execution = QueryExecutionFactory.create(query, model, initialBinding)) {
                execution.execSelect().forEachRemaining(solution -> rows.add(ResponseRow.from(solution)));
            }
        }
        return rows;
    }

    private Map<String, Long> tallyOutcomes(List<ResponseRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ResponseRow row : rows) {
            String result = row.result() != null ? localName(row.result()) : row.resultValue();
            String key = defaulted(localName(row.status())) + " | " + defaulted(result) + " | " + defaulted(row.comment());
            counts.merge(key, 1L, Long::sum);
        }
        return counts;
    }

    private Map<String, Long> tallyTermValues(List<ResponseRow> rows, String term) {
        Property property = model.createProperty(DWC, term);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ResponseRow row : rows) {
            if (row.record() == null) {
                continue;
            }
            Resource recordResource = model.createResource(row.record());
            StmtIterator values = recordResource.listProperties(property);
            if (!values.hasNext()) {
                counts.merge("<no value>", 1L, Long::sum);
            }
            while (values.hasNext()) {
                Statement statement = values.nextStatement();
                String value = statement.getObject().isLiteral()
                        ? statement.getObject().asLiteral().getString()
                        : statement.getObject().toString();
                counts.merge(value, 1L, Long::sum);
            }
        }
        return counts;
    }

    private static void appendTopCounts(StringBuilder builder, String title, Map<String, Long> counts) {
        builder.append(title).append(":\n");
        if (counts.isEmpty()) {
            builder.append("    - none\n");
            return;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
        entries.stream()
                .limit(TOP_N)
                .forEach(entry -> builder.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));
        if (entries.size() > TOP_N) {
            builder.append("    ... and ").append(entries.size() - TOP_N).append(" more distinct value(s)\n");
        }
    }

    private String literalValue(Resource resource, String propertyUri) {
        Statement statement = resource.getProperty(model.createProperty(propertyUri));
        return statement != null && statement.getObject().isLiteral() ? statement.getString() : null;
    }

    private String resourceUri(Resource resource, String propertyUri) {
        Statement statement = resource.getProperty(model.createProperty(propertyUri));
        return statement != null && statement.getObject().isResource() ? statement.getResource().getURI() : null;
    }

    private static String localName(String uri) {
        if (uri == null) {
            return null;
        }
        int cut = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('#'));
        return cut >= 0 ? uri.substring(cut + 1) : uri;
    }

    private static String defaulted(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private static String joinAsDwcTerms(List<String> terms) {
        return terms.stream().map(term -> "dwc:" + term).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private record TestMetadata(String label, String description, String dimension, String criterion, String enhancement) {
    }

    private record ResponseRow(String status, String result, String resultValue, String comment, String phase, String record) {
        private static ResponseRow from(QuerySolution solution) {
            return new ResponseRow(
                    uriOrNull(solution, "status"),
                    uriOrNull(solution, "result"),
                    literalOrNull(solution, "resultValue"),
                    literalOrNull(solution, "comment"),
                    literalOrNull(solution, "phase"),
                    uriOrNull(solution, "record"));
        }

        private static String uriOrNull(QuerySolution solution, String variable) {
            RDFNode node = solution.get(variable);
            if (node == null) {
                return null;
            }
            return node.isResource() ? node.asResource().getURI() : node.toString();
        }

        private static String literalOrNull(QuerySolution solution, String variable) {
            RDFNode node = solution.get(variable);
            if (node == null) {
                return null;
            }
            return node.isLiteral() ? node.asLiteral().getString() : node.toString();
        }
    }
}
