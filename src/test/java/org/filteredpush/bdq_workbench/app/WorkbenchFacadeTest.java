package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
                new AppConfig(Path.of("usecase.xml"), List.of(), Path.of("dataset.zip"), "uc1", List.of("org.filteredpush"), 1),
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
}
