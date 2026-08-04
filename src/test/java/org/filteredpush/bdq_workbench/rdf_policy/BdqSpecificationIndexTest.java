package org.filteredpush.bdq_workbench.rdf_policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BdqSpecificationIndexTest {

    @TempDir
    Path tempDir;

    private BdqSpecificationIndex index;

    @BeforeEach
    void loadExcerpt() throws Exception {
        Path excerpt = tempDir.resolve("bdqtest_excerpt.ttl");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("bdq/bdqtest_excerpt.ttl")) {
            Files.copy(in, excerpt);
        }
        index = new BdqSpecificationIndex(List.of(excerpt));
    }

    @Test
    void resolvesSpecificationFromTheDatedVersionedTestIri() {
        Optional<String> specification = index.specificationIriFor(
                "https://rs.tdwg.org/bdqtest/terms/0493bcfb-652e-4d17-815b-b0cce0742fbe-2025-03-07",
                TestType.VALIDATION);

        assertThat(specification).contains("urn:uuid:01b96157-e4a1-4884-95d7-3bcfc5f3c047");
    }

    @Test
    void resolvesSpecificationFromTheBareTestIriViaIsVersionOf() {
        Optional<String> specification = index.specificationIriFor(
                "https://rs.tdwg.org/bdqtest/terms/0493bcfb-652e-4d17-815b-b0cce0742fbe",
                TestType.VALIDATION);

        assertThat(specification).contains("urn:uuid:01b96157-e4a1-4884-95d7-3bcfc5f3c047");
    }

    @Test
    void resolvesRegardlessOfTestTypeWhenUnknown() {
        Optional<String> specification = index.specificationIriFor(
                "https://rs.tdwg.org/bdqtest/terms/0493bcfb-652e-4d17-815b-b0cce0742fbe-2025-03-07",
                TestType.UNKNOWN);

        assertThat(specification).contains("urn:uuid:01b96157-e4a1-4884-95d7-3bcfc5f3c047");
    }

    @Test
    void returnsEmptyForATestNotInTheLoadedDefinitions() {
        Optional<String> specification = index.specificationIriFor(
                "https://example.org/not-a-real-test",
                TestType.VALIDATION);

        assertThat(specification).isEmpty();
    }

    @Test
    void returnsEmptyForWrongTestType() {
        Optional<String> specification = index.specificationIriFor(
                "https://rs.tdwg.org/bdqtest/terms/0493bcfb-652e-4d17-815b-b0cce0742fbe-2025-03-07",
                TestType.AMENDMENT);

        assertThat(specification).isEmpty();
    }
}
