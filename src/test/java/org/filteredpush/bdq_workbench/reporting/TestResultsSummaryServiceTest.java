package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

class TestResultsSummaryServiceTest {

    private static final String VERSIONED_TEST_IRI =
            "https://rs.tdwg.org/bdqtest/terms/0493bcfb-652e-4d17-815b-b0cce0742fbe-2025-03-07";

    /** More than the top-10 cap, to exercise truncation. */
    private static final String[] COUNTRY_CODES = {
        "US", "US", "US", "CA", "GL", "MX", "BR", "AR", "CL", "PE", "CO", "VE", "EC", "BO"
    };

    @TempDir
    Path tempDir;

    @Test
    void summarizesTestDefinitionAndPerPhaseResponsesWithValueTruncation() throws Exception {
        Path rdfDefinitions = copyClasspathResource("bdq/bdqtest_excerpt.ttl", "bdqtest_excerpt.ttl");
        Path resultsFile = exportSyntheticResults(rdfDefinitions);

        TestResultsSummaryService service = new TestResultsSummaryService(List.of(rdfDefinitions, resultsFile));
        String summary = service.summarize(VERSIONED_TEST_IRI, TestType.VALIDATION);

        assertThat(summary).contains("Label: VALIDATION_COUNTRYCODE_STANDARD");
        assertThat(summary).contains("Is the value of dwc:countryCode a valid ISO 3166-1-alpha-2 country code?");
        assertThat(summary).contains("Dimension: Conformance");
        assertThat(summary).contains("Criterion: Standard");
        assertThat(summary).contains("Acts upon: dwc:countryCode");

        assertThat(summary).contains("Phase: PRE_AMENDMENT (" + COUNTRY_CODES.length + " responses)");
        assertThat(summary).contains("RUN_HAS_RESULT | COMPLIANT");
        assertThat(summary).contains("US: 3");
        // 12 distinct country codes were used; only the top 10 should be listed, with a note.
        assertThat(summary).contains("... and 2 more distinct value(s)");
    }

    @Test
    void reportsNoResponsesForATestNotInTheResults() throws Exception {
        Path rdfDefinitions = copyClasspathResource("bdq/bdqtest_excerpt.ttl", "bdqtest_excerpt.ttl");
        Path resultsFile = exportSyntheticResults(rdfDefinitions);

        TestResultsSummaryService service = new TestResultsSummaryService(List.of(rdfDefinitions, resultsFile));
        String summary = service.summarize("https://example.org/not-run", TestType.VALIDATION);

        assertThat(summary).contains("No responses found for this test in the run's RDF results.");
    }

    private Path exportSyntheticResults(Path rdfDefinitions) throws Exception {
        List<Response> responses = new ArrayList<>();
        List<CanonicalRecord> records = new ArrayList<>();
        for (int i = 0; i < COUNTRY_CODES.length; i++) {
            String recordId = "r" + i;
            records.add(new CanonicalRecord(recordId, Map.of("dwc:countryCode", COUNTRY_CODES[i])));
            responses.add(new Response(
                    recordId,
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
                    Instant.now()));
        }
        ExecutionSummary summary = new ExecutionSummary(
                responses,
                new ExecutionSummaryMetadata("urn:usecase:1", "Use Case One", "/tmp/input.csv", 1, records.size(), Map.of(), Map.of()),
                new RecordDataset(records));

        Path resultsFile = tempDir.resolve("bdq-report-rdf.ttl");
        RdfResponseExporter exporter = new RdfResponseExporter(List.of(rdfDefinitions));
        try (OutputStream out = Files.newOutputStream(resultsFile)) {
            exporter.export(summary, out);
        }
        return resultsFile;
    }

    private Path copyClasspathResource(String classpathLocation, String fileName) throws Exception {
        Path target = tempDir.resolve(fileName);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathLocation)) {
            Files.copy(in, target);
        }
        return target;
    }
}
