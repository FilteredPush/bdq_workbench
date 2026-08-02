package org.filteredpush.bdq_workbench.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.filteredpush.bdq_workbench.execution.TestExecutionService;
import org.filteredpush.bdq_workbench.ingest.IngestService;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.PreparedRun;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.rdf_policy.PolicyResolverService;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingResult;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingService;
import org.filteredpush.bdq_workbench.test_discovery.TestDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** High-level orchestrator for ingestion, resolution, discovery, execution, and reporting. */
public class WorkbenchFacade {
	
	private static final Logger LOG = LoggerFactory.getLogger(WorkbenchFacade.class);
	
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

    public PreparedRun prepare(AppConfig config) {
        var dataset = ingestService.ingest(config.datasetPath());
        ExecutionPlan plan = policyResolverService.resolve(config.useCaseId());
        List<DiscoveredImplementation> discovered = testDiscoveryService.discover();
        TestBindingResult bindingResult = testBindingService.bind(
                plan.tests(),
                discovered,
                java.util.Map.of(),
                collectAvailableTerms(dataset));
        return new PreparedRun(config, dataset, plan, List.copyOf(discovered), bindingResult);
    }

    public ExecutionSummary run(AppConfig config) {
        return runPrepared(prepare(config));
    }

    public ExecutionSummary runPrepared(PreparedRun preparedRun) {
        var dataset = preparedRun.dataset();
        ExecutionPlan plan = preparedRun.plan();
        List<DiscoveredImplementation> discovered = preparedRun.discovered();
        TestBindingResult bindingResult = preparedRun.bindingResult();

        LOG.info("Executing {} tests with {} discovered implementations",
                bindingResult.bindings().size(),
                discovered.size());

        List<Response> responses = new ArrayList<>(executionService.execute(
                dataset,
                bindingResult.bindings(),
                discovered));

        for (var unresolved : plan.unresolvedTests()) {
            responses.add(new Response(
                    "*",
                    unresolved.id(),
                    unresolved.type(),
                    "",
                    "",
                    unresolved.phase(),
                    unresolved.parameters(),
                    OutcomeStatus.UNABLE_TO_RUN,
                    "UNABLE_TO_RUN",
                    "UNABLE_TO_RUN",
                    "Unresolved in policy resolution",
                    "Unresolved in policy resolution",
                    java.util.Map.of(),
                    java.time.Instant.now(),
                    java.time.Instant.now()));
        }
        for (var unresolved : bindingResult.unresolved()) {
            String detail = bindingResult.reviews().stream()
                    .filter(review -> review.test().id().equals(unresolved.id()))
                    .findFirst()
                    .map(review -> String.join("; ", review.diagnostics()))
                    .filter(message -> !message.isBlank())
                    .orElse("No implementation discovered");
            responses.add(new Response(
                    "*",
                    unresolved.id(),
                    unresolved.type(),
                    "",
                    "",
                    unresolved.phase(),
                    unresolved.parameters(),
                    OutcomeStatus.UNABLE_TO_RUN,
                    "UNABLE_TO_RUN",
                    "UNABLE_TO_RUN",
                    detail,
                    detail,
                    java.util.Map.of(),
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

    private static Set<String> collectAvailableTerms(org.filteredpush.bdq_workbench.model.RecordDataset dataset) {
        Set<String> terms = new LinkedHashSet<>();
        dataset.records().forEach(record -> terms.addAll(record.terms().keySet()));
        return terms;
    }
}
