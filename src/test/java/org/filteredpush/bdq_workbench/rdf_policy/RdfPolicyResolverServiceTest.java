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
}
