package org.filteredpush.bdq_workbench.test_discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
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
    void treatsMissingDwCTermAsEmptyStringBinding() throws Exception {
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
            assertThat(binding.bindingStatus()).isEqualTo(BindingStatus.BOUND);
            assertThat(binding.diagnostics()).anyMatch(message -> message.contains(
                    "empty string")
                    && message.contains(
                    "Term acted_upon/consulted absent in input data: dwc:eventDate"));
            assertThat(binding.parameterBindings()).singleElement().satisfies(parameter ->
                    assertThat(parameter.resolvedSource()).isEqualTo("dwc:eventDate"));
        });
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void resolvesSupportedCountMeasureAsBuiltInBinding() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();

        DiscoveredImplementation discovered = new DiscoveredImplementation(
                "urn:test:validation",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "validate",
                null,
                List.of(),
                new Dummy(),
                Dummy.class.getMethod("validate"));

        TestDefinition validation = new TestDefinition(
                "urn:test:validation",
                "VALIDATION_BASISOFRECORD_NOTEMPTY",
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Map.of());
        TestDefinition measure = new TestDefinition(
                "urn:test:measure",
                "MULTIRECORD_MEASURE_COUNT_COMPLIANT_BASISOFRECORD_NOTEMPTY",
                TestType.MEASURE,
                Phase.PRE_AMENDMENT,
                Map.of());

        TestBindingResult result = service.bind(
                List.of(validation, measure),
                List.of(discovered),
                Map.of(),
                Set.of());

        assertThat(result.unresolved()).isEmpty();
        assertThat(result.bindings()).hasSize(2);
        var measureBinding = result.bindings().stream()
                .filter(binding -> binding.testId().equals("urn:test:measure"))
                .findFirst()
                .orElseThrow();
        assertThat(measureBinding.bindingStatus()).isEqualTo(BindingStatus.BOUND);
        assertThat(measureBinding.implementationClass()).isEqualTo(BuiltInMeasureSpec.IMPLEMENTATION_CLASS);
        assertThat(measureBinding.implementationMethod()).isEqualTo(BuiltInMeasureSpec.IMPLEMENTATION_METHOD);
        assertThat(measureBinding.parameters())
                .containsEntry(BuiltInMeasureSpec.KIND_KEY, BuiltInMeasureSpec.MeasureKind.COUNT.name())
                .containsEntry(BuiltInMeasureSpec.TARGET_TEST_ID_KEY, validation.id())
                .containsEntry(BuiltInMeasureSpec.RESPONSE_RESULT_KEY, "COMPLIANT");

        var measureReview = result.reviews().stream()
                .filter(review -> review.test().id().equals("urn:test:measure"))
                .findFirst()
                .orElseThrow();
        assertThat(measureReview.implementationStatus()).isEqualTo(ImplementationStatus.FOUND);
        assertThat(measureReview.bindingStatus()).isEqualTo(BindingStatus.BOUND);
        assertThat(measureReview.diagnostics()).contains("Built-in multi-record COUNT measure");
    }

    @Test
    void bindsBuiltInQaMeasureUsingExpectedResponseMetadata() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();
        DiscoveredImplementation discovered = new DiscoveredImplementation(
                "urn:test:validation",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "validate",
                null,
                List.of(),
                new Dummy(),
                Dummy.class.getMethod("validate"));

        TestDefinition validation = new TestDefinition(
                "urn:test:validation",
                "VALIDATION_MINDEPTH_LESSTHAN_MAXDEPTH",
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Map.of());
        TestDefinition measure = new TestDefinition(
                "urn:test:qa",
                "MULTIRECORD_MEASURE_QA_MINDEPTH_LESSTHAN_MAXDEPTH",
                TestType.MEASURE,
                Phase.PRE_AMENDMENT,
                Map.of(),
                Map.of(BuiltInMeasureSpec.EXPECTED_RESPONSE_METADATA_KEY,
                        "COMPLETE if every VALIDATION_MINDEPTH_LESSTHAN_MAXDEPTH in the MultiRecord has Response.result=COMPLIANT or Response.status=INTERNAL_PREREQUISITES_NOT_MET, otherwise NOT_COMPLETE."));

        TestBindingResult result = service.bind(
                List.of(validation, measure),
                List.of(discovered),
                Map.of(),
                Set.of());

        var measureBinding = result.bindings().stream()
                .filter(binding -> binding.testId().equals("urn:test:qa"))
                .findFirst()
                .orElseThrow();
        assertThat(measureBinding.parameters())
                .containsEntry(BuiltInMeasureSpec.KIND_KEY, BuiltInMeasureSpec.MeasureKind.QA.name())
                .containsEntry(BuiltInMeasureSpec.TARGET_TEST_ID_KEY, validation.id())
                .containsEntry(BuiltInMeasureSpec.ACCEPTABLE_RESPONSE_RESULTS_KEY, "COMPLIANT")
                .containsEntry(BuiltInMeasureSpec.ACCEPTABLE_RESPONSE_STATUSES_KEY, "INTERNAL_PREREQUISITES_NOT_MET");
    }

    @Test
    void diagnosesNamespaceMismatchBetweenRdfAndImplementationParameters() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();
        DiscoveredImplementation discovered = new DiscoveredImplementation(
                "urn:test:namespace",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "parameterized",
                null,
                List.of(
                        parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class),
                        parameter(1, ParameterRole.PARAMETER, "bdq:sourceAuthority", String.class)),
                new Dummy(),
                Dummy.class.getMethod("parameterizedWithSourceAuthority", String.class, String.class));

        TestBindingResult result = service.bind(
                List.of(new TestDefinition(
                        "urn:test:namespace",
                        "Test",
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        Map.of("bdqval:sourceAuthority", "ISO"))),
                List.of(discovered),
                Map.of(),
                Set.of("dwc:eventDate"));

        assertThat(result.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.bindingStatus()).isEqualTo(BindingStatus.UNBOUND);
            assertThat(binding.diagnostics()).anyMatch(message -> message.contains("RDF parameter bdqval:sourceAuthority")
                    && message.contains("bdq:sourceAuthority"));
            assertThat(binding.diagnostics()).anyMatch(message -> message.contains("PARAMETER NAMESPACE MISMATCH"));
        });
        assertThat(result.unresolved()).extracting(TestDefinition::id).containsExactly("urn:test:namespace");
    }

    @Test
    void prefersCandidateWithFullRdfParameterCoverage() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();
        DiscoveredImplementation partialCoverage = new DiscoveredImplementation(
                "urn:test:coverage",
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
        DiscoveredImplementation fullCoverage = new DiscoveredImplementation(
                "urn:test:coverage",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "parameterizedFull",
                null,
                List.of(
                        parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class),
                        parameter(1, ParameterRole.PARAMETER, "bdq:earliestValidDate", Integer.class),
                        parameter(2, ParameterRole.PARAMETER, "bdq:latestValidDate", Integer.class)),
                new Dummy(),
                Dummy.class.getMethod("parameterizedFull", String.class, Integer.class, Integer.class));

        TestBindingResult result = service.bind(
                List.of(new TestDefinition(
                        "urn:test:coverage",
                        "Test",
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        Map.of(
                                "bdq:earliestValidDate", "1900",
                                "bdq:latestValidDate", "2026"))),
                List.of(partialCoverage, fullCoverage),
                Map.of(),
                Set.of("dwc:eventDate"));

        assertThat(result.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.implementationMethod()).isEqualTo("parameterizedFull");
            assertThat(binding.bindingStatus()).isEqualTo(BindingStatus.BOUND);
        });
    }

    @Test
    void marksEquallyCompatibleCandidatesAmbiguousAndNonRunnable() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();
        DiscoveredImplementation first = new DiscoveredImplementation(
                "urn:test:ambiguous",
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
        DiscoveredImplementation second = new DiscoveredImplementation(
                "urn:test:ambiguous",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "parameterizedDuplicate",
                null,
                List.of(
                        parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class),
                        parameter(1, ParameterRole.PARAMETER, "bdq:latestValidDate", Integer.class)),
                new Dummy(),
                Dummy.class.getMethod("parameterizedDuplicate", String.class, Integer.class));

        TestBindingResult result = service.bind(
                List.of(new TestDefinition(
                        "urn:test:ambiguous",
                        "Test",
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        Map.of("bdq:latestValidDate", "2026"))),
                List.of(first, second),
                Map.of(),
                Set.of("dwc:eventDate"));

        assertThat(result.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.bindingStatus()).isEqualTo(BindingStatus.UNBOUND);
            assertThat(binding.diagnostics()).anyMatch(message -> message.contains("AMBIGUOUS"));
        });
        assertThat(result.reviews()).singleElement().satisfies(review ->
                assertThat(review.implementationStatus()).isEqualTo(ImplementationStatus.AMBIGUOUS));
    }

    @Test
    void stillBindsUnqualifiedLocalNameParameterAliases() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();
        DiscoveredImplementation discovered = new DiscoveredImplementation(
                "urn:test:alias",
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
                        "urn:test:alias",
                        "Test",
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        Map.of("latestValidDate", "2026"))),
                List.of(discovered),
                Map.of(),
                Set.of("dwc:eventDate"));

        assertThat(result.bindings()).singleElement().satisfies(binding ->
                assertThat(binding.bindingStatus()).isEqualTo(BindingStatus.BOUND));
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void builtInMeasureIsRetainedForDiagnosticsWhenTargetBindingIsNotRunnable() throws Exception {
        DefaultTestBindingService service = new DefaultTestBindingService();
        DiscoveredImplementation validationImplementation = new DiscoveredImplementation(
                "urn:test:validation",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Dummy.class.getName(),
                "validate",
                null,
                List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)),
                new Dummy(),
                Dummy.class.getMethod("validate"));
        TestDefinition validation = new TestDefinition(
                "urn:test:validation",
                "VALIDATION_BASISOFRECORD_NOTEMPTY",
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Map.of());
        TestDefinition measure = new TestDefinition(
                "urn:test:measure",
                "MULTIRECORD_MEASURE_QA_BASISOFRECORD_NOTEMPTY",
                TestType.MEASURE,
                Phase.PRE_AMENDMENT,
                Map.of());

        TestBindingResult result = service.bind(
                List.of(validation, measure),
                List.of(validationImplementation),
                Map.of(),
                Set.of("dwc:scientificName"));

        assertThat(result.bindings().stream()
                .filter(binding -> binding.testId().equals("urn:test:measure"))
                .findFirst()
                .orElseThrow())
                .satisfies(binding -> {
                    assertThat(binding.bindingStatus()).isEqualTo(BindingStatus.UNBOUND);
                    assertThat(binding.diagnostics()).anyMatch(message -> message.contains("retained for diagnostics only"));
                });
        assertThat(result.unresolved()).extracting(TestDefinition::id)
                .contains("urn:test:validation", "urn:test:measure");
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

        public boolean parameterizedFull(String value, Integer earliestValidDate, Integer latestValidDate) {
            return value != null && earliestValidDate != null && latestValidDate != null;
        }

        public boolean parameterizedStringDefaults(String value, String earliestValidDate, String latestValidDate) {
            return value != null;
        }

        public boolean parameterizedWithSourceAuthority(String value, String sourceAuthority) {
            return value != null && sourceAuthority != null;
        }

        public boolean parameterizedDuplicate(String value, Integer latestValidDate) {
            return value != null && latestValidDate != null;
        }
    }
}
