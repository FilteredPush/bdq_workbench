package org.filteredpush.bdq_workbench.rdf_policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UseCaseXmlParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesSimpleLowercaseUsecaseAttributes() throws Exception {
        Path xml = tempDir.resolve("simple.xml");
        Files.writeString(xml, """
                <usecases>
                  <usecase id="uc1" name="Use Case One" policy="urn:policy:one" />
                </usecases>
                """, StandardCharsets.UTF_8);

        Map<String, UseCase> useCases = UseCaseXmlParser.loadUseCases(xml);

        assertThat(useCases).containsKey("uc1");
        assertThat(useCases.get("uc1").policyId()).isEqualTo("urn:policy:one");
    }

    @Test
    void parsesNamespacedUseCaseAndPolicyResource() throws Exception {
        Path xml = tempDir.resolve("namespaced.xml");
        Files.writeString(xml, """
                <bdq:UseCases xmlns:bdq="https://example.org/bdq" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <bdq:UseCase rdf:about="https://example.org/usecase/uc2" name="Use Case Two">
                    <bdq:policy rdf:resource="https://example.org/policy/p2" />
                  </bdq:UseCase>
                </bdq:UseCases>
                """, StandardCharsets.UTF_8);

        Map<String, UseCase> useCases = UseCaseXmlParser.loadUseCases(xml);

        assertThat(useCases).containsKey("https://example.org/usecase/uc2");
        assertThat(useCases.get("https://example.org/usecase/uc2").policyId())
                .isEqualTo("https://example.org/policy/p2");
    }

    @Test
    void parsesRdfXmlUseCases() throws Exception {
        Path rdfXml = tempDir.resolve("bdquc.xml");
        Files.writeString(rdfXml, """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                         xmlns:bdq="https://rs.tdwg.org/bdq/terms/">
                  <rdf:Description rdf:about="https://example.org/usecase/uc3">
                    <rdf:type rdf:resource="https://example.org/ontology#UseCase"/>
                    <rdfs:label>Use Case Three</rdfs:label>
                    <bdq:mapsTo rdf:resource="https://example.org/policy/p3"/>
                  </rdf:Description>
                </rdf:RDF>
                """, StandardCharsets.UTF_8);

        Map<String, UseCase> useCases = UseCaseXmlParser.loadUseCases(rdfXml);

        assertThat(useCases).containsKey("https://example.org/usecase/uc3");
        assertThat(useCases.get("https://example.org/usecase/uc3").policyId())
                .isEqualTo("https://example.org/policy/p3");
    }

    @Test
    void parsesTurtleAndJsonLdUseCases() throws Exception {
        Path turtle = tempDir.resolve("bdquc.ttl");
        Files.writeString(turtle, """
                @prefix ex: <https://example.org/> .
                @prefix bdq: <https://rs.tdwg.org/bdq/terms/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                ex:uc4 a ex:UseCase ;
                    rdfs:label "Use Case Four" ;
                    bdq:hasQualityProfile ex:p4 .
                """, StandardCharsets.UTF_8);

        Path jsonld = tempDir.resolve("bdquc.jsonld");
        Files.writeString(jsonld, """
                {
                  "@context": {
                    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                    "rdfs": "http://www.w3.org/2000/01/rdf-schema#",
                    "bdq": "https://rs.tdwg.org/bdq/terms/"
                  },
                  "@id": "https://example.org/usecase/uc5",
                  "@type": "https://example.org/ontology#UseCase",
                  "rdfs:label": "Use Case Five",
                  "bdq:hasQualityProfile": {
                    "@id": "https://example.org/policy/p5"
                  }
                }
                """, StandardCharsets.UTF_8);

        Map<String, UseCase> turtleUseCases = UseCaseXmlParser.loadUseCases(turtle);
        Map<String, UseCase> jsonLdUseCases = UseCaseXmlParser.loadUseCases(jsonld);

        assertThat(turtleUseCases).containsKey("https://example.org/uc4");
        assertThat(turtleUseCases.get("https://example.org/uc4").policyId())
                .isEqualTo("https://example.org/p4");
        assertThat(jsonLdUseCases).containsKey("https://example.org/usecase/uc5");
        assertThat(jsonLdUseCases.get("https://example.org/usecase/uc5").policyId())
                .isEqualTo("https://example.org/policy/p5");
    }
}
