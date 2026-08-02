package org.filteredpush.bdq_workbench.test_discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.ImplementationStatus;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class DefaultTestBindingServiceTest {

    @Test
    void prefersProvidesThenExplicitMappingAndTracksUnresolved() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();

        DiscoveredImplementation discoveredProvided = new DiscoveredImplementation(
                "urn:test:1",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "validate",
                null,
                List.of(),
                new Dummy(),
                Dummy.class.getMethod("validate"));

        DiscoveredImplementation discoveredMapped = new DiscoveredImplementation(
                "urn:test:unused",
                null,
                TestType.VALIDATION,
                Phase.POST_AMENDMENT,
                Dummy.class.getName(),
                "post",
                null,
                List.of(),
                new Dummy(),
                Dummy.class.getMethod("post"));

        TestBindingResult result = service.bind(
                List.of(
                        new TestDefinition("urn:test:1", "A", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of()),
                        new TestDefinition("urn:test:2", "B", TestType.VALIDATION, Phase.POST_AMENDMENT, Map.of()),
                        new TestDefinition("urn:test:3", "C", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of())),
                List.of(discoveredProvided, discoveredMapped),
                Map.of("urn:test:2", Dummy.class.getName() + "#post"));

        assertThat(result.bindings()).hasSize(2);
        assertThat(result.bindings()).extracting("testId").containsExactlyInAnyOrder("urn:test:1", "urn:test:2");
        assertThat(result.unresolved()).extracting(TestDefinition::id).containsExactly("urn:test:3");
        assertThat(result.reviews()).hasSize(3);
    }

    @Test
    void prefersExactProvidesVersionOverProvidesFallback() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();

        DiscoveredImplementation exactVersion = new DiscoveredImplementation(
                "3cff4dc4-72e9-4abe-9bf3-8a30f1618432",
                "https://rs.tdwg.org/bdqtest/terms/3cff4dc4-72e9-4abe-9bf3-8a30f1618432-2025-03-06",
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "validate",
                null,
                List.of(),
                new Dummy(),
                Dummy.class.getMethod("validate"));

        DiscoveredImplementation fallbackOnly = new DiscoveredImplementation(
                "3cff4dc4-72e9-4abe-9bf3-8a30f1618432",
                "https://rs.tdwg.org/bdqtest/terms/3cff4dc4-72e9-4abe-9bf3-8a30f1618432-2025-01-01",
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "post",
                null,
                List.of(),
                new Dummy(),
                Dummy.class.getMethod("post"));

        TestBindingResult result = service.bind(
                List.of(new TestDefinition(
                        "https://rs.tdwg.org/bdqtest/terms/3cff4dc4-72e9-4abe-9bf3-8a30f1618432-2025-03-06",
                        "VALIDATION_EVENTTEMPORAL_NOTEMPTY",
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        Map.of())),
                List.of(exactVersion, fallbackOnly),
                Map.of());

        assertThat(result.bindings()).hasSize(1);
        assertThat(result.bindings().get(0).implementationMethod()).isEqualTo("validate");
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void prefersParameterizedMethodWhenUserProvidesValues() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();

        DiscoveredImplementation defaultMethod = new DiscoveredImplementation(
                "urn:test:param",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "validate",
                null,
                List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)),
                new Dummy(),
                Dummy.class.getMethod("validate"));
        DiscoveredImplementation parameterizedMethod = new DiscoveredImplementation(
                "urn:test:param",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "parameterized",
                null,
                List.of(
                        parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class),
                        parameter(1, ParameterRole.PARAMETER, "bdq:latestValidDate", Integer.class)),
                new Dummy(),
                Dummy.class.getMethod("parameterized", String.class, Integer.class));

        TestBindingResult result = service.bind(
                List.of(new TestDefinition(
                        "urn:test:param",
                        "Test",
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        Map.of("bdq:latestValidDate", "2026"))),
                List.of(defaultMethod, parameterizedMethod),
                Map.of(),
                Set.of("dwc:eventDate"));

        assertThat(result.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.implementationMethod()).isEqualTo("parameterized");
            assertThat(binding.parameterizationCapability()).isEqualTo(ParameterizationCapability.BOTH);
            assertThat(binding.bindingStatus()).isEqualTo(BindingStatus.BOUND);
        });
        assertThat(result.reviews()).singleElement().satisfies(review -> {
            assertThat(review.implementationStatus()).isEqualTo(ImplementationStatus.FOUND);
            assertThat(review.diagnostics()).contains("Parameterized version available");
        });
    }

    @Test
    void bindsParameterizedOnlyMethodWhenReferenceParametersUseImplementationDefaults() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();

        DiscoveredImplementation parameterizedMethod = new DiscoveredImplementation(
                "urn:test:param-defaults",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "parameterizedStringDefaults",
                null,
                List.of(
                        parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class),
                        parameter(1, ParameterRole.PARAMETER, "bdq:earliestValidDate", String.class),
                        parameter(2, ParameterRole.PARAMETER, "bdq:latestValidDate", String.class)),
                new Dummy(),
                Dummy.class.getMethod("parameterizedStringDefaults", String.class, String.class, String.class));

        TestBindingResult result = service.bind(
                List.of(new TestDefinition(
                        "urn:test:param-defaults",
                        "Test",
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        Map.of())),
                List.of(parameterizedMethod),
                Map.of(),
                Set.of("dwc:eventDate"));

        assertThat(result.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.implementationMethod()).isEqualTo("parameterizedStringDefaults");
            assertThat(binding.bindingStatus()).isEqualTo(BindingStatus.BOUND);
            assertThat(binding.usingDefaultParameters()).isTrue();
            assertThat(binding.diagnostics())
                    .contains("BOUND: all parameters compatible");
        });
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void reportsMissingDwCTermAsTermMissingDiagnostic() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();

        DiscoveredImplementation discovered = new DiscoveredImplementation(
                "urn:test:missing-term",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "validate",
                null,
                List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)),
                new Dummy(),
                Dummy.class.getMethod("validate"));

        TestBindingResult result = service.bind(
                List.of(new TestDefinition("urn:test:missing-term", "Test", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of())),
                List.of(discovered),
                Map.of(),
                Set.of("dwc:scientificName"));

        assertThat(result.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.bindingStatus()).isEqualTo(BindingStatus.TERM_MISSING);
            assertThat(binding.diagnostics()).anyMatch(message -> message.contains(
                    "TERM MISSING")
                    && message.contains(
                    "Term acted_upon/consulted absent in input data: dwc:eventDate"));
        });
    }

    private static MethodParameter parameter(int index, ParameterRole role, String source, Class<?> type) {
        return new MethodParameter(index, "p" + index, role, source, type.getName(), true);
    }

    static class Dummy {
        public boolean validate() {
            return true;
        }

        public boolean post() {
            return true;
        }

        public boolean parameterized(String value, Integer latestValidDate) {
            return value != null && latestValidDate != null;
        }

        public boolean parameterizedStringDefaults(String value, String earliestValidDate, String latestValidDate) {
            return value != null;
        }
    }
}
