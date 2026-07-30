package org.filteredpush.bdq_workbench.app;

import java.util.ArrayList;
import java.util.List;
import org.filteredpush.bdq_workbench.execution.TestExecutionService;
import org.filteredpush.bdq_workbench.ingest.IngestService;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.rdf_policy.PolicyResolverService;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingResult;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingService;
import org.filteredpush.bdq_workbench.test_discovery.TestDiscoveryService;

/** High-level orchestrator for ingestion, resolution, discovery, execution, and reporting. */
public class WorkbenchFacade {
    private final IngestService ingestService;
    private final PolicyResolverService policyResolverService;
    private final TestDiscoveryService testDiscoveryService;
    private final TestBindingService testBindingService;
    private final TestExecutionService executionService;
    private final ReportingService reportingService;

    public WorkbenchFacade(
            IngestService ingestService,
            PolicyResolverService policyResolverService,
            TestDiscoveryService testDiscoveryService,
            TestBindingService testBindingService,
            TestExecutionService executionService,
            ReportingService reportingService) {
        this.ingestService = ingestService;
        this.policyResolverService = policyResolverService;
        this.testDiscoveryService = testDiscoveryService;
        this.testBindingService = testBindingService;
        this.executionService = executionService;
        this.reportingService = reportingService;
    }

    public ExecutionSummary run(AppConfig config) {
        var dataset = ingestService.ingest(config.datasetPath());
        ExecutionPlan plan = policyResolverService.resolve(config.useCaseId());
        List<DiscoveredImplementation> discovered = testDiscoveryService.discover();
        TestBindingResult bindingResult = testBindingService.bind(plan.tests(), discovered, java.util.Map.of());

        List<Response> responses = new ArrayList<>(executionService.execute(
                dataset,
                bindingResult.bindings(),
                discovered));

        for (var unresolved : plan.unresolvedTests()) {
            responses.add(new Response(
                    "*",
                    unresolved.id(),
                    "",
                    "",
                    unresolved.phase(),
                    unresolved.parameters(),
                    OutcomeStatus.NOT_IMPLEMENTED,
                    "Unresolved in policy resolution",
                    java.time.Instant.now(),
                    java.time.Instant.now()));
        }
        for (var unresolved : bindingResult.unresolved()) {
            responses.add(new Response(
                    "*",
                    unresolved.id(),
                    "",
                    "",
                    unresolved.phase(),
                    unresolved.parameters(),
                    OutcomeStatus.NOT_IMPLEMENTED,
                    "No implementation discovered",
                    java.time.Instant.now(),
                    java.time.Instant.now()));
        }

        responses.sort(java.util.Comparator
                .comparing(Response::phase)
                .thenComparing(Response::testId)
                .thenComparing(Response::recordId));
        ExecutionSummary summary = new ExecutionSummary(List.copyOf(responses));
        reportingService.export(summary);
        return summary;
    }
}
