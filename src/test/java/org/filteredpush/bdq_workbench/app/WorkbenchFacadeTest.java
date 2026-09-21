package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.filteredpush.bdq_workbench.filtering.DefaultRecordFilterService;
import org.filteredpush.bdq_workbench.execution.TestExecutionService;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ImplementationStatus;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Policy;
import org.filteredpush.bdq_workbench.model.PreparedRun;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordFilterSpec;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingResult;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingService;
import org.junit.jupiter.api.Test;

class WorkbenchFacadeTest {

    @Test
    void prepareFiltersDatasetBeforeBinding() {
        RecordDataset ingested = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:country", "Canada", "dwc:genus", "Abies")),
                new CanonicalRecord("r2", Map.of("dwc:country", "Mexico", "dwc:genus", "Abies"))));
        TestDefinition test = new TestDefinition("urn:test:validation", "Validation", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of());
        AtomicReference<Set<String>> availableTermsSeen = new AtomicReference<>(Set.of());
        TestBindingService bindingService = new TestBindingService() {
            @Override
            public TestBindingResult bind(
                    List<TestDefinition> tests,
                    List<DiscoveredImplementation> discovered,
                    Map<String, String> explicitMapping) {
                return new TestBindingResult(List.of(), List.of(), List.of());
            }

            @Override
            public TestBindingResult bind(
                    List<TestDefinition> tests,
                    List<DiscoveredImplementation> discovered,
                    Map<String, String> explicitMapping,
                    java.util.Collection<String> availableTerms) {
                availableTermsSeen.set(Set.copyOf(availableTerms));
                return new TestBindingResult(List.of(), List.of(), List.of());
            }
        };
        WorkbenchFacade facade = new WorkbenchFacade(
                inputPath -> ingested,
                useCaseId -> new ExecutionPlan(
                        new UseCase("uc1", "Use Case", "policy:1"),
                        new Policy("policy:1", List.of(test.id())),
                        List.of(test),
                        List.of()),
                () -> List.of(),
                bindingService,
                (dataset, bindings, discovered) -> List.of(),
                new ReportingService(List.of()),
                new DefaultRecordFilterService());

        PreparedRun prepared = facade.prepare(new AppConfig(
                Path.of("usecase.xml"),
                List.of(),
                Path.of("dataset.zip"),
                "uc1",
                List.of("org.filteredpush"),
                1,
                true,
                RecordFilterSpec.parse("country=Canada")));

        assertThat(prepared.dataset().records()).extracting(CanonicalRecord::id).containsExactly("r1");
        assertThat(prepared.filterSummary().originalRecordCount()).isEqualTo(2);
        assertThat(prepared.filterSummary().filteredRecordCount()).isEqualTo(1);
        assertThat(availableTermsSeen.get()).containsExactlyInAnyOrder("dwc:country", "dwc:genus");
    }

    @Test
    void runPreparedMarksUnresolvedTestsAsUnableToRun() {
        TestDefinition policyUnresolved =
                new TestDefinition("urn:test:policy", "Policy unresolved", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of());
        TestDefinition bindingUnresolved =
                new TestDefinition("urn:test:binding", "Binding unresolved", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of());
        PreparedRun preparedRun = new PreparedRun(
                new AppConfig(Path.of("usecase.xml"), List.of(), Path.of("dataset.zip"), "uc1", List.of("org.filteredpush"), 1, true),
                new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "2025-01-01")))),
                new ExecutionPlan(
                        new UseCase("uc1", "Use Case", "policy:1"),
                        new Policy("policy:1", List.of(policyUnresolved.id(), bindingUnresolved.id())),
                        List.of(),
                        List.of(policyUnresolved)),
                List.of(),
                new TestBindingResult(
                        List.of(),
                        List.of(bindingUnresolved),
                        List.of(new BindingReview(
                                bindingUnresolved,
                                ImplementationStatus.MISSING,
                                BindingStatus.UNBOUND,
                                ParameterizationCapability.DEFAULT_ONLY,
                                "",
                                Map.of(),
                                true,
                                List.of("No implementation discovered for urn:test:binding")))));

        TestExecutionService executionService = (dataset, bindings, discovered) -> List.of();
        WorkbenchFacade facade = new WorkbenchFacade(
                null,
                null,
                null,
                null,
                executionService,
                new ReportingService(List.of()));

        var summary = facade.runPrepared(preparedRun);

        assertThat(summary.responses())
                .extracting(response -> response.testId() + ":" + response.responseStatus() + ":" + response.responseResult())
                .contains(
                        "urn:test:policy:UNABLE_TO_RUN:UNABLE_TO_RUN",
                        "urn:test:binding:UNABLE_TO_RUN:UNABLE_TO_RUN");
    }

    @Test
    void runPreparedAttachesSummaryMetadataFromRunContext() {
        TestDefinition amendment =
                new TestDefinition("urn:test:amend", "Amend", TestType.AMENDMENT, Phase.AMENDMENT, Map.of());
        PreparedRun preparedRun = new PreparedRun(
                new AppConfig(Path.of("usecase.xml"), List.of(), Path.of("input.zip"), "uc1", List.of("org.filteredpush"), 1, true),
                new RecordDataset(List.of(
                        new CanonicalRecord("r1", Map.of("dwc:countryCode", "SU", "dwc:eventDate", "")),
                        new CanonicalRecord("r2", Map.of("dwc:countryCode", "", "dwc:basisOfRecord", "HumanObservation")))),
                new ExecutionPlan(
                        new UseCase("uc1", "Use Case", "policy:1"),
                        new Policy("policy:1", List.of(amendment.id())),
                        List.of(amendment),
                        List.of()),
                List.of(),
                new TestBindingResult(List.of(), List.of(), List.of()));

        TestExecutionService executionService = (dataset, bindings, discovered) -> List.of(
                new Response(
                        "r1",
                        amendment.id(),
                        TestType.AMENDMENT,
                        "example.Impl",
                        "amend",
                        Phase.AMENDMENT,
                        Map.of(),
                        org.filteredpush.bdq_workbench.model.OutcomeStatus.AMENDED,
                        "AMENDED",
                        "{dwc:countryCode=RU}",
                        "updated",
                        "updated",
                        Map.of("dwc:countryCode", "RU"),
                        Instant.now(),
                        Instant.now()),
                new Response(
                        "r2",
                        amendment.id(),
                        TestType.AMENDMENT,
                        "example.Impl",
                        "fill",
                        Phase.AMENDMENT,
                        Map.of(),
                        org.filteredpush.bdq_workbench.model.OutcomeStatus.AMENDED,
                        "FILLED_IN",
                        "{dwc:countryCode=RU}",
                        "filled",
                        "filled",
                        Map.of("dwc:countryCode", "RU"),
                        Instant.now(),
                        Instant.now()));
        WorkbenchFacade facade = new WorkbenchFacade(
                null,
                null,
                null,
                null,
                executionService,
                new ReportingService(List.of()));

        var summary = facade.runPrepared(preparedRun);

        assertThat(summary.metadata().useCaseId()).isEqualTo("uc1");
        assertThat(summary.metadata().useCaseLabel()).isEqualTo("Use Case");
        assertThat(summary.metadata().inputFile()).isEqualTo("input.zip");
        assertThat(summary.metadata().inputDarwinCoreTermCount()).isEqualTo(3);
        assertThat(summary.metadata().inputSingleRecordCount()).isEqualTo(2);
        assertThat(summary.metadata().filteredDarwinCoreTermCount()).isEqualTo(3);
        assertThat(summary.metadata().filteredSingleRecordCount()).isEqualTo(2);
        assertThat(summary.metadata().filledInValueCounts()).containsEntry("dwc:countryCode=RU", 1L);
        assertThat(summary.metadata().amendedValuePairCounts()).containsEntry("dwc:countryCode: SU -> RU", 1L);
    }

    @Test
    void runPreparedExecutesOnlyRunnableBindingsAndSynthesizesNonRunnableOutcomes() {
        TestDefinition runnableTest =
                new TestDefinition("urn:test:runnable", "Runnable", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of());
        TestDefinition nonRunnableTest =
                new TestDefinition("urn:test:non-runnable", "Non Runnable", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of());
        ImplementationBinding runnableBinding = new ImplementationBinding(
                runnableTest.id(),
                runnableTest.type(),
                "example.Impl",
                "run",
                runnableTest.phase(),
                Map.of(),
                BindingStatus.BOUND,
                ParameterizationCapability.DEFAULT_ONLY,
                "selected",
                true,
                List.of(),
                List.of("BOUND: all parameters compatible"));
        ImplementationBinding nonRunnableBinding = new ImplementationBinding(
                nonRunnableTest.id(),
                nonRunnableTest.type(),
                "example.Impl",
                "skip",
                nonRunnableTest.phase(),
                Map.of(),
                BindingStatus.UNBOUND,
                ParameterizationCapability.DEFAULT_ONLY,
                "selected",
                true,
                List.of(),
                List.of("Missing parameter value for bdq:sourceAuthority"));
        PreparedRun preparedRun = new PreparedRun(
                new AppConfig(Path.of("usecase.xml"), List.of(), Path.of("dataset.zip"), "uc1", List.of("org.filteredpush"), 1, true),
                new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "2025-01-01")))),
                new ExecutionPlan(
                        new UseCase("uc1", "Use Case", "policy:1"),
                        new Policy("policy:1", List.of(runnableTest.id(), nonRunnableTest.id())),
                        List.of(runnableTest, nonRunnableTest),
                        List.of()),
                List.of(),
                new TestBindingResult(
                        List.of(runnableBinding, nonRunnableBinding),
                        List.of(nonRunnableTest),
                        List.of(
                                new BindingReview(
                                        runnableTest,
                                        ImplementationStatus.FOUND,
                                        BindingStatus.BOUND,
                                        ParameterizationCapability.DEFAULT_ONLY,
                                        "example.Impl#run()",
                                        Map.of(),
                                        true,
                                        List.of("BOUND: all parameters compatible")),
                                new BindingReview(
                                        nonRunnableTest,
                                        ImplementationStatus.FOUND,
                                        BindingStatus.UNBOUND,
                                        ParameterizationCapability.DEFAULT_ONLY,
                                        "example.Impl#skip()",
                                        Map.of(),
                                        true,
                                        List.of("Missing parameter value for bdq:sourceAuthority")))));

        AtomicReference<List<ImplementationBinding>> executedBindings = new AtomicReference<>(List.of());
        TestExecutionService executionService = (dataset, bindings, discovered) -> {
            executedBindings.set(List.copyOf(bindings));
            return List.of();
        };
        WorkbenchFacade facade = new WorkbenchFacade(
                null,
                null,
                null,
                null,
                executionService,
                new ReportingService(List.of()));

        ExecutionSummary summary = facade.runPrepared(preparedRun);

        assertThat(executedBindings.get()).extracting(ImplementationBinding::testId).containsExactly("urn:test:runnable");
        assertThat(summary.responses()).extracting(Response::testId, Response::responseStatus, Response::responseResult)
                .contains(org.assertj.core.groups.Tuple.tuple(
                        "urn:test:non-runnable",
                        "UNABLE_TO_RUN",
                        "UNABLE_TO_RUN"));
    }
}
