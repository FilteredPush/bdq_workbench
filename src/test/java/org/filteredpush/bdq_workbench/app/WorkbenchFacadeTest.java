package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.execution.TestExecutionService;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ImplementationStatus;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Policy;
import org.filteredpush.bdq_workbench.model.PreparedRun;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingResult;
import org.junit.jupiter.api.Test;

class WorkbenchFacadeTest {

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
        assertThat(summary.metadata().darwinCoreTermCount()).isEqualTo(3);
        assertThat(summary.metadata().singleRecordCount()).isEqualTo(2);
        assertThat(summary.metadata().filledInValueCounts()).containsEntry("dwc:countryCode=RU", 1L);
        assertThat(summary.metadata().amendedValuePairCounts()).containsEntry("dwc:countryCode: SU -> RU", 1L);
    }
}
