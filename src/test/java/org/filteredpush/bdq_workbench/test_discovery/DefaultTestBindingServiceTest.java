package org.filteredpush.bdq_workbench.test_discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.junit.jupiter.api.Test;

class DefaultTestBindingServiceTest {

    @Test
    void prefersProvidesThenExplicitMappingAndTracksUnresolved() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();

        DiscoveredImplementation discoveredProvided = new DiscoveredImplementation(
                "urn:test:1",
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "validate",
                Map.of(),
                new Dummy(),
                Dummy.class.getMethod("validate"));

        DiscoveredImplementation discoveredMapped = new DiscoveredImplementation(
                "urn:test:unused",
                Phase.POST_AMENDMENT,
                Dummy.class.getName(),
                "post",
                Map.of(),
                new Dummy(),
                Dummy.class.getMethod("post"));

        TestBindingResult result = service.bind(
                List.of(
                        new TestDefinition("urn:test:1", "A", Phase.PRE_AMENDMENT, Map.of()),
                        new TestDefinition("urn:test:2", "B", Phase.POST_AMENDMENT, Map.of()),
                        new TestDefinition("urn:test:3", "C", Phase.PRE_AMENDMENT, Map.of())),
                List.of(discoveredProvided, discoveredMapped),
                Map.of("urn:test:2", Dummy.class.getName() + "#post"));

        assertThat(result.bindings()).hasSize(2);
        assertThat(result.bindings()).extracting("testId").containsExactlyInAnyOrder("urn:test:1", "urn:test:2");
        assertThat(result.unresolved()).extracting(TestDefinition::id).containsExactly("urn:test:3");
    }

    static class Dummy {
        public boolean validate() {
            return true;
        }

        public boolean post() {
            return true;
        }
    }
}
