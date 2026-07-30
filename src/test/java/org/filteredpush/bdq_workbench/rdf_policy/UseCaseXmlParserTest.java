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
}
