package org.filteredpush.bdq_workbench.rdf_policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RdfPolicyResolverServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesTestsFromPoliciesLinkedByHasUseCase() throws Exception {
        Path useCaseFile = tempDir.resolve("bdquc.xml");
        Files.writeString(useCaseFile, """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                         xmlns:dcterms="http://purl.org/dc/terms/">
                  <rdf:Description rdf:about="https://rs.tdwg.org/bdquc/terms/version/Alien-Species-2026-04-22">
                    <rdf:type rdf:resource="https://rs.tdwg.org/bdqffdq/terms/UseCase"/>
                    <rdfs:label>Alien-Species</rdfs:label>
                    <dcterms:isVersionOf rdf:resource="https://rs.tdwg.org/bdquc/terms/Alien-Species"/>
                  </rdf:Description>
                </rdf:RDF>
                """, StandardCharsets.UTF_8);

        Path definitions = tempDir.resolve("bdqtest.ttl");
        Files.writeString(definitions, """
                @prefix bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/> .
                @prefix bdquc: <https://rs.tdwg.org/bdquc/terms/> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

                ex:policy1 a bdqffdq:ValidationPolicy ;
                    bdqffdq:hasUseCase bdquc:Alien-Species ;
                    bdqffdq:hasTest ex:test1 .

                ex:test1 a bdqffdq:Validation ;
                    rdfs:label "VALIDATION_EXAMPLE_ONE" .
                """, StandardCharsets.UTF_8);

        RdfPolicyResolverService service = new RdfPolicyResolverService(useCaseFile, List.of(definitions));

        var plan = service.resolve("https://rs.tdwg.org/bdquc/terms/version/Alien-Species-2026-04-22");

        assertThat(plan.useCase().policyId()).isEqualTo("https://rs.tdwg.org/bdquc/terms/Alien-Species");
        assertThat(plan.policy().testIds()).contains("https://example.org/test1");
        assertThat(plan.tests()).hasSize(1);
        assertThat(plan.tests().get(0).id()).isEqualTo("https://example.org/test1");
    }

    @Test
    void resolvesTestsWhenPolicyUsesVersionedOrUnversionedIriVariants() throws Exception {
        Path useCaseFile = tempDir.resolve("bdquc.xml");
        Files.writeString(useCaseFile, """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                         xmlns:dcterms="http://purl.org/dc/terms/">
                  <rdf:Description rdf:about="https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22">
                    <rdf:type rdf:resource="https://rs.tdwg.org/bdqffdq/terms/UseCase"/>
                    <rdfs:label>Spatial-Temporal Patterns</rdfs:label>
                    <dcterms:isVersionOf rdf:resource="https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns"/>
                  </rdf:Description>
                </rdf:RDF>
                """, StandardCharsets.UTF_8);

        Path definitions = tempDir.resolve("bdqtest.ttl");
        Files.writeString(definitions, """
                @prefix bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

                ex:policyVersioned a bdqffdq:ValidationPolicy ;
                    bdqffdq:hasUseCase <http://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22> ;
                    bdqffdq:hasTest ex:testVersioned .

                ex:policyUnversioned a bdqffdq:ValidationPolicy ;
                    bdqffdq:hasUseCase <http://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns> ;
                    bdqffdq:hasTest ex:testUnversioned .

                ex:testVersioned a bdqffdq:Validation ;
                    rdfs:label "TEST_VERSIONED" .
                ex:testUnversioned a bdqffdq:Validation ;
                    rdfs:label "TEST_UNVERSIONED" .
                """, StandardCharsets.UTF_8);

        RdfPolicyResolverService service = new RdfPolicyResolverService(useCaseFile, List.of(definitions));

        var plan = service.resolve("https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22");

        assertThat(plan.policy().testIds())
                .containsExactlyInAnyOrder("https://example.org/testVersioned", "https://example.org/testUnversioned");
        assertThat(plan.tests()).hasSize(2);
    }

    @Test
    void resolvesTestsFromPolicyPredicatesContainingTestToken() throws Exception {
        Path useCaseFile = tempDir.resolve("bdquc.xml");
        Files.writeString(useCaseFile, """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                         xmlns:dcterms="http://purl.org/dc/terms/">
                  <rdf:Description rdf:about="https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22">
                    <rdf:type rdf:resource="https://rs.tdwg.org/bdqffdq/terms/UseCase"/>
                    <rdfs:label>Spatial-Temporal Patterns</rdfs:label>
                    <dcterms:isVersionOf rdf:resource="https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns"/>
                  </rdf:Description>
                </rdf:RDF>
                """, StandardCharsets.UTF_8);

        Path definitions = tempDir.resolve("bdqtest.ttl");
        Files.writeString(definitions, """
                @prefix bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

                ex:policy1 a bdqffdq:ValidationPolicy ;
                    bdqffdq:hasUseCase <https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns> ;
                    bdqffdq:hasSingleRecordTest ex:testSingle ;
                    bdqffdq:hasMultiRecordMeasure ex:testMeasure .

                ex:testSingle a bdqffdq:Validation ;
                    rdfs:label "TEST_SINGLE" .
                ex:testMeasure a bdqffdq:Measure ;
                    rdfs:label "TEST_MEASURE" .
                """, StandardCharsets.UTF_8);

        RdfPolicyResolverService service = new RdfPolicyResolverService(useCaseFile, List.of(definitions));
        var plan = service.resolve("https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22");

        assertThat(plan.policy().testIds())
                .containsExactlyInAnyOrder("https://example.org/testSingle", "https://example.org/testMeasure");
        assertThat(plan.tests().stream().map(test -> test.id()).toList())
                .containsExactlyInAnyOrder("https://example.org/testSingle", "https://example.org/testMeasure");
    }

    @Test
    void resolvesTestsFromValidationPolicyLinksAndDataQualityNeedSubclassTests() throws Exception {
        Path useCaseFile = tempDir.resolve("bdquc.xml");
        Files.writeString(useCaseFile, """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                         xmlns:dcterms="http://purl.org/dc/terms/">
                  <rdf:Description rdf:about="https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22">
                    <rdf:type rdf:resource="https://rs.tdwg.org/bdqffdq/terms/UseCase"/>
                    <rdfs:label>Spatial-Temporal Patterns</rdfs:label>
                    <dcterms:isVersionOf rdf:resource="https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns"/>
                  </rdf:Description>
                </rdf:RDF>
                """, StandardCharsets.UTF_8);

        Path definitions = tempDir.resolve("bdqtest.ttl");
        Files.writeString(definitions, """
                @prefix bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

                ex:policy1 a bdqffdq:ValidationPolicy ;
                    bdqffdq:hasUseCase <https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns> ;
                    bdqffdq:hasValidation ex:testValidation .

                ex:testValidation rdfs:subClassOf bdqffdq:DataQualityNeed ;
                    rdfs:label "TEST_VALIDATION" .
                """, StandardCharsets.UTF_8);

        RdfPolicyResolverService service = new RdfPolicyResolverService(useCaseFile, List.of(definitions));
        var plan = service.resolve("https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22");

        assertThat(plan.policy().testIds()).containsExactly("https://example.org/testValidation");
        assertThat(plan.tests()).hasSize(1);
        assertThat(plan.tests().get(0).id()).isEqualTo("https://example.org/testValidation");
    }

    @Test
    void resolvesTestsFromHasDataQualityNeedPolicyPredicate() throws Exception {
        Path useCaseFile = tempDir.resolve("bdquc.xml");
        Files.writeString(useCaseFile, """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                         xmlns:dcterms="http://purl.org/dc/terms/">
                  <rdf:Description rdf:about="https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22">
                    <rdf:type rdf:resource="https://rs.tdwg.org/bdqffdq/terms/UseCase"/>
                    <rdfs:label>Spatial-Temporal Patterns</rdfs:label>
                    <dcterms:isVersionOf rdf:resource="https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns"/>
                  </rdf:Description>
                </rdf:RDF>
                """, StandardCharsets.UTF_8);

        Path definitions = tempDir.resolve("bdqtest.ttl");
        Files.writeString(definitions, """
                @prefix bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

                ex:policy1 a bdqffdq:ValidationPolicy ;
                    bdqffdq:hasUseCase <https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns> ;
                    bdqffdq:hasDataQualityNeed ex:testNeed .

                ex:testNeed a bdqffdq:Validation ;
                    rdfs:label "TEST_NEED" .
                """, StandardCharsets.UTF_8);

        RdfPolicyResolverService service = new RdfPolicyResolverService(useCaseFile, List.of(definitions));
        var plan = service.resolve("https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22");

        assertThat(plan.policy().testIds()).containsExactly("https://example.org/testNeed");
        assertThat(plan.tests()).hasSize(1);
        assertThat(plan.tests().get(0).id()).isEqualTo("https://example.org/testNeed");
    }

    @Test
    void resolvesTestsUsingOntologyRangeAndDataQualityNeedSubclassSemantics() throws Exception {
        Path useCaseFile = tempDir.resolve("bdquc.xml");
        Files.writeString(useCaseFile, """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                         xmlns:dcterms="http://purl.org/dc/terms/">
                  <rdf:Description rdf:about="https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22">
                    <rdf:type rdf:resource="https://rs.tdwg.org/bdqffdq/terms/UseCase"/>
                    <rdfs:label>Spatial-Temporal Patterns</rdfs:label>
                    <dcterms:isVersionOf rdf:resource="https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns"/>
                  </rdf:Description>
                </rdf:RDF>
                """, StandardCharsets.UTF_8);

        Path definitions = tempDir.resolve("bdqtest.ttl");
        Files.writeString(definitions, """
                @prefix bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

                ex:policy1 a bdqffdq:ValidationPolicy ;
                    bdqffdq:hasUseCase <https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns> ;
                    ex:requiresNeed ex:testNeed .

                ex:requiresNeed rdfs:range bdqffdq:Issue .
                bdqffdq:Issue rdfs:subClassOf bdqffdq:DataQualityNeed .

                ex:testNeed rdf:type bdqffdq:Issue ;
                    rdfs:label "TEST_NEED_BY_OWL_SEMANTICS" .
                """, StandardCharsets.UTF_8);

        RdfPolicyResolverService service = new RdfPolicyResolverService(useCaseFile, List.of(definitions));
        var plan = service.resolve("https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22");

        assertThat(plan.policy().testIds()).containsExactly("https://example.org/testNeed");
        assertThat(plan.tests()).hasSize(1);
        assertThat(plan.tests().get(0).id()).isEqualTo("https://example.org/testNeed");
    }

    @Test
    void summarizesDefinitionSourcesWithUseCasePolicyAndTestCounts() throws Exception {
        Path definitions = tempDir.resolve("bdqtest.ttl");
        Files.writeString(definitions, """
                @prefix bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

                ex:policy1 a bdqffdq:ValidationPolicy ;
                    bdqffdq:hasUseCase <https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns> ;
                    bdqffdq:hasDataQualityNeed ex:testNeed .

                ex:testNeed a bdqffdq:Validation ;
                    rdfs:label "TEST_NEED" .
                """, StandardCharsets.UTF_8);

        var summary = RdfPolicyResolverService.summarizeDefinitionSources(List.of(definitions));

        assertThat(summary.files()).hasSize(1);
        assertThat(summary.files().get(0).useCaseCount()).isEqualTo(1);
        assertThat(summary.files().get(0).policyCount()).isEqualTo(1);
        assertThat(summary.files().get(0).testCount()).isEqualTo(1);
        assertThat(summary.totalUseCases()).isEqualTo(1);
        assertThat(summary.totalPolicies()).isEqualTo(1);
        assertThat(summary.totalTests()).isEqualTo(1);
    }

    @Test
    void capturesExpectedResponseAndNoteMetadataForTests() throws Exception {
        Path useCaseFile = tempDir.resolve("bdquc.xml");
        Files.writeString(useCaseFile, """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                         xmlns:dcterms="http://purl.org/dc/terms/"
                         xmlns:skos="http://www.w3.org/2004/02/skos/core#">
                  <rdf:Description rdf:about="https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22">
                    <rdf:type rdf:resource="https://rs.tdwg.org/bdqffdq/terms/UseCase"/>
                    <rdfs:label>Spatial-Temporal Patterns</rdfs:label>
                    <dcterms:isVersionOf rdf:resource="https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns"/>
                  </rdf:Description>
                </rdf:RDF>
                """, StandardCharsets.UTF_8);

        Path definitions = tempDir.resolve("bdqtest.ttl");
        Files.writeString(definitions, """
                @prefix bdqffdq: <https://rs.tdwg.org/bdqffdq/terms/> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

                ex:policy1 a bdqffdq:ValidationPolicy ;
                    bdqffdq:hasUseCase <https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns> ;
                    bdqffdq:hasTest ex:testMeasure .

                ex:testMeasure a bdqffdq:Measure ;
                    rdfs:label "MULTIRECORD_MEASURE_QA_MINDEPTH_LESSTHAN_MAXDEPTH" ;
                    bdqffdq:hasExpectedResponse "COMPLETE if every VALIDATION_MINDEPTH_LESSTHAN_MAXDEPTH in the MultiRecord has Response.result=COMPLIANT or Response.status=INTERNAL_PREREQUISITES_NOT_MET, otherwise NOT_COMPLETE." ;
                    skos:note "For Quality Assurance, filter record set until this measure is COMPLETE." .
                """, StandardCharsets.UTF_8);

        RdfPolicyResolverService service = new RdfPolicyResolverService(useCaseFile, List.of(definitions));
        var plan = service.resolve("https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22");

        assertThat(plan.tests()).singleElement().satisfies(test -> {
            assertThat(test.metadata()).containsEntry("expectedResponse",
                    "COMPLETE if every VALIDATION_MINDEPTH_LESSTHAN_MAXDEPTH in the MultiRecord has Response.result=COMPLIANT or Response.status=INTERNAL_PREREQUISITES_NOT_MET, otherwise NOT_COMPLETE.");
            assertThat(test.metadata()).containsEntry("note",
                    "For Quality Assurance, filter record set until this measure is COMPLETE.");
        });
    }
}
