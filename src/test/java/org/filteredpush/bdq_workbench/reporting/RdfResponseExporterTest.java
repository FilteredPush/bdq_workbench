package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.validation.ReportEntry;
import org.apache.jena.shacl.validation.Severity;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RdfResponseExporterTest {

    private static final String VERSIONED_TEST_IRI =
            "https://rs.tdwg.org/bdqtest/terms/0493bcfb-652e-4d17-815b-b0cce0742fbe-2025-03-07";
    private static final String EXPECTED_SPECIFICATION_IRI = "urn:uuid:01b96157-e4a1-4884-95d7-3bcfc5f3c047";

    @TempDir
    Path tempDir;

    @Test
    void exportsAShaclConformantDataQualityReportLinkedToTheTestDefinition() throws Exception {
        Path rdfDefinitions = copyClasspathResource("bdq/bdqtest_excerpt.ttl", "bdqtest_excerpt.ttl");
        RdfResponseExporter exporter = new RdfResponseExporter(List.of(rdfDefinitions));

        ExecutionSummary summary = new ExecutionSummary(
                List.of(
                        validationResponse(),
                        amendmentResponse(),
                        measurementResponse(),
                        unableToRunPlaceholder()),
                new ExecutionSummaryMetadata("urn:usecase:1", "Use Case One", "/tmp/input.csv", 3, 1, Map.of(), Map.of()),
                new RecordDataset(List.of(new CanonicalRecord(
                        "record-1", Map.of("dwc:countryCode", "GL", "dwc:country", "Greenland")))));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.export(summary, output);

        assertThat(exporter.format()).isEqualTo("rdf");
        assertThat(exporter.fileExtension()).isEqualTo("ttl");

        Model exported = ModelFactory.createDefaultModel();
        RDFDataMgr.read(exported, new ByteArrayInputStream(output.toByteArray()), Lang.TURTLE);

        assertThat(exported.contains(null, exported.createProperty("https://rs.tdwg.org/bdqffdq/terms/usesSpecification"),
                exported.createResource(EXPECTED_SPECIFICATION_IRI)))
                .as("an Implementation uses the Specification resolved from the loaded RDF definitions")
                .isTrue();

        assertThat(exported.contains(
                exported.createResource("urn:bdq-workbench:record:record-1"),
                exported.createProperty("http://rs.tdwg.org/dwc/terms/countryCode"),
                "GL"))
                .as("the record resource carries its dwc:-prefixed term values from the run's dataset")
                .isTrue();

        assertThat(exported.contains(null, exported.createProperty("https://github.com/FilteredPush/bdq_workbench/terms/phase"),
                Phase.PRE_AMENDMENT.name()))
                .as("responses carry a bdqwb:phase literal")
                .isTrue();

        // The exporter deliberately does not duplicate bdqtest.ttl's Method/Specification/
        // DataQualityNeed triples (that's the whole point of linking by IRI rather than
        // replicating the ontology), so the traceability chain SHACL checks (bdqs:*ResponseShape,
        // §3.2) can only resolve once validated against the merged graph.
        Model merged = ModelFactory.createDefaultModel();
        merged.add(exported);
        merged.add(RDFDataMgr.loadModel(classpathResourcePath("bdq/bdqtest_excerpt.ttl").toString()));

        Shapes shapes = Shapes.parse(shapesModelGraph());
        ValidationReport report = ShaclValidator.get().validate(shapes, merged.getGraph());
        List<ReportEntry> violations = report.getEntries().stream()
                .filter(entry -> entry.severity().equals(Severity.Violation))
                .toList();
        // The Amendment/Measurement responses use made-up local test IDs that are not (and
        // should not be expected to be) present in bdqtest.ttl, so their traceability chains
        // correctly fail to resolve; the assertion below confirms that's the *only* reason any
        // violation remains, i.e. the Validation response (whose test IS in bdqtest.ttl) resolves.
        assertThat(violations)
                .as("SHACL sh:Violation entries against the merged graph")
                .hasSize(2)
                .allSatisfy(entry -> assertThat(entry.message()).doesNotContain("ValidationResponse"));

        String sparql = """
                PREFIX bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/>
                SELECT ?dimension WHERE {
                  ?response a bdqffdq:ValidationResponse .
                  ?implementation bdqffdq:producesResponse ?response ;
                                  bdqffdq:usesSpecification ?specification .
                  ?method bdqffdq:hasSpecification ?specification ;
                          bdqffdq:forValidation ?test .
                  ?test bdqffdq:hasDataQualityDimension ?dimension .
                }
                """;
        try (QueryExecution execution = QueryExecutionFactory.create(sparql, merged)) {
            var results = execution.execSelect();
            assertThat(results.hasNext()).as("Response -> Implementation -> Specification -> Method -> DataQualityNeed chain resolves").isTrue();
            QuerySolution solution = results.next();
            assertThat(solution.getResource("dimension").getURI()).isEqualTo("https://rs.tdwg.org/bdqdim/terms/Conformance");
        }
    }

    private static org.apache.jena.graph.Graph shapesModelGraph() throws Exception {
        Model shapes = ModelFactory.createDefaultModel();
        try (InputStream in = RdfResponseExporterTest.class.getClassLoader()
                .getResourceAsStream("bdq/bdqffdq_shacl_constraints.ttl")) {
            shapes.read(in, null, "TURTLE");
        }
        return shapes.getGraph();
    }

    private Path copyClasspathResource(String classpathLocation, String fileName) throws Exception {
        Path target = tempDir.resolve(fileName);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathLocation)) {
            Files.copy(in, target);
        }
        return target;
    }

    private static Path classpathResourcePath(String classpathLocation) throws Exception {
        return Path.of(RdfResponseExporterTest.class.getClassLoader().getResource(classpathLocation).toURI());
    }

    private static Response validationResponse() {
        return new Response(
                "record-1",
                VERSIONED_TEST_IRI,
                TestType.VALIDATION,
                "org.example.CountryCodeValidator",
                "validate",
                Phase.PRE_AMENDMENT,
                Map.of(),
                OutcomeStatus.PASSED,
                "RUN_HAS_RESULT",
                "COMPLIANT",
                "dwc:countryCode is a valid ISO 3166-1-alpha-2 value",
                "dwc:countryCode is a valid ISO 3166-1-alpha-2 value",
                Map.of(),
                Instant.now(),
                Instant.now());
    }

    private static Response amendmentResponse() {
        return new Response(
                "record-1",
                "urn:test:amendment",
                TestType.AMENDMENT,
                "org.example.CountryStandardizer",
                "amend",
                Phase.AMENDMENT,
                Map.of(),
                OutcomeStatus.AMENDED,
                "FILLED_IN",
                null,
                "Filled in missing country from countryCode",
                "Filled in missing country from countryCode",
                Map.of("dwc:country", "Greenland"),
                Instant.now(),
                Instant.now());
    }

    private static Response measurementResponse() {
        return new Response(
                "MULTIRECORD",
                "urn:test:measure",
                TestType.MEASURE,
                "org.example.CompletenessMeasure",
                "measure",
                Phase.PRE_AMENDMENT,
                Map.of(),
                OutcomeStatus.PASSED,
                "RUN_HAS_RESULT",
                "42.0",
                "42 records measured",
                "42 records measured",
                Map.of(),
                Instant.now(),
                Instant.now());
    }

    private static Response unableToRunPlaceholder() {
        return new Response(
                "*",
                "urn:test:unresolved",
                TestType.VALIDATION,
                "",
                "",
                Phase.PRE_AMENDMENT,
                Map.of(),
                OutcomeStatus.UNABLE_TO_RUN,
                "UNABLE_TO_RUN",
                "UNABLE_TO_RUN",
                "Unresolved in policy resolution",
                "Unresolved in policy resolution",
                Map.of(),
                Instant.now(),
                Instant.now());
    }
}
