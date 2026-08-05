/** WorkbenchFacade.java
 *
 * High-level orchestrator for ingestion, resolution, discovery, execution, and reporting.
 *
 * Copyright 2026 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.filteredpush.bdq_workbench.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.filteredpush.bdq_workbench.execution.TestExecutionService;
import org.filteredpush.bdq_workbench.ingest.IngestService;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
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

/**
 * High-level orchestrator for ingestion, resolution, discovery, execution, and reporting.
 *
 * <p>Wires together the individual pipeline services (ingest, policy resolution, test
 * discovery, test binding, execution, and reporting) into the two operations that make up a
 * BDQ workbench run:
 *
 * <ol>
 *   <li>{@link #prepare(AppConfig)} — ingest the dataset, resolve the use case's policy into
 *       an {@link ExecutionPlan}, discover available test implementations, and bind the
 *       plan's tests to those implementations, producing a {@link PreparedRun} that can be
 *       inspected (e.g. for a preflight review of what will and will not run) before any test
 *       is actually executed.
 *   <li>{@link #runPrepared(PreparedRun)} (or {@link #run(AppConfig)}, which combines both
 *       steps) — execute the bound tests against the ingested records, synthesize
 *       {@link org.filteredpush.bdq_workbench.model.OutcomeStatus#UNABLE_TO_RUN} responses for
 *       any tests that could not be resolved or bound, and export the resulting
 *       {@link ExecutionSummary} via the {@link ReportingService}.
 * </ol>
 */
public class WorkbenchFacade {

	private static final Logger LOG = LoggerFactory.getLogger(WorkbenchFacade.class);

    private final IngestService ingestService;
    private final PolicyResolverService policyResolverService;
    private final TestDiscoveryService testDiscoveryService;
    private final TestBindingService testBindingService;
    private final TestExecutionService executionService;
    private final ReportingService reportingService;

    /**
     * Creates a facade wired to the given pipeline services.
     *
     * @param ingestService service that ingests Darwin Core input into canonical records
     * @param policyResolverService service that resolves a use case identifier into an
     *     {@link ExecutionPlan} of executable tests
     * @param testDiscoveryService service that discovers available test implementations
     * @param testBindingService service that binds resolved tests to discovered implementations
     * @param executionService service that executes bound tests against canonical records
     * @param reportingService service that exports an {@link ExecutionSummary} to the
     *     configured report formats
     */
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

    /**
     * Ingests the configured dataset, resolves the configured use case, discovers available
     * test implementations, and binds the resolved tests to those implementations, without
     * executing anything.
     *
     * <p>The returned {@link PreparedRun} captures everything needed to run the tests (or to
     * review, ahead of execution, which tests are bound versus unresolved) and can be passed
     * to {@link #runPrepared(PreparedRun)}.
     *
     * @param config application configuration specifying the dataset path, use case identifier,
     *     and implementation packages to scan
     * @return the prepared run, ready for execution
     */
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

    /**
     * Prepares and then executes a full run for the given configuration.
     *
     * <p>Equivalent to {@code runPrepared(prepare(config))}.
     *
     * @param config application configuration specifying the dataset path, use case identifier,
     *     and implementation packages to scan
     * @return the summary of the executed run, including responses for any unresolved or
     *     unbound tests
     */
    public ExecutionSummary run(AppConfig config) {
        return runPrepared(prepare(config));
    }

    /**
     * Executes the tests bound in {@code preparedRun} against its ingested dataset, then
     * augments the results with synthesized {@code UNABLE_TO_RUN} responses for tests that
     * policy resolution or test binding could not resolve, and exports the resulting summary.
     *
     * <p>For every test in {@link ExecutionPlan#unresolvedTests()} (rejected during policy
     * resolution) and every test in {@link TestBindingResult#unresolved()} (rejected during
     * binding, because no matching implementation was discovered), a placeholder
     * {@link Response} is added with {@link OutcomeStatus#UNABLE_TO_RUN}, so that every test
     * referenced by the use case's policy is represented in the final summary. The unresolved
     * binding responses include diagnostic detail drawn from
     * {@link TestBindingResult#reviews()} where available. All responses — executed and
     * synthesized — are sorted by phase, then test ID, then record ID before the summary is
     * built and passed to the {@link ReportingService} for export.
     *
     * @param preparedRun the dataset, plan, and bindings produced by {@link #prepare(AppConfig)}
     * @return the summary of responses (executed and synthesized) and their aggregated metadata,
     *     as exported by the reporting service
     */
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
        ExecutionSummary summary = new ExecutionSummary(
                List.copyOf(responses),
                buildSummaryMetadata(preparedRun, responses),
                preparedRun.dataset(),
                bindingResult.bindings());
        reportingService.export(summary);
        return summary;
    }

    /**
     * Builds the aggregated metadata (use case identity, dataset path, term/record counts, and
     * value-change summaries) attached to an {@link ExecutionSummary}.
     *
     * @param preparedRun the prepared run the responses were produced from
     * @param responses all responses (executed and synthesized) for the run
     * @return metadata summarizing the run and its outcomes
     */
    private static ExecutionSummaryMetadata buildSummaryMetadata(PreparedRun preparedRun, List<Response> responses) {
        var dataset = preparedRun.dataset();
        var useCase = preparedRun.plan().useCase();
        java.util.Map<String, java.util.Map<String, String>> sourceTermsByRecordId = dataset.records().stream()
                .collect(java.util.stream.Collectors.toMap(
                        org.filteredpush.bdq_workbench.model.CanonicalRecord::id,
                        record -> java.util.Map.copyOf(record.terms()),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
        return new ExecutionSummaryMetadata(
                useCase.id(),
                useCase.label(),
                preparedRun.config() == null || preparedRun.config().datasetPath() == null
                        ? ""
                        : preparedRun.config().datasetPath().toString(),
                collectAvailableTerms(dataset).size(),
                dataset.records().size(),
                summarizeFilledInValues(responses),
                summarizeAmendedValuePairs(responses, sourceTermsByRecordId));
    }

    /**
     * Tallies how many times each {@code term=value} pair was produced by an AMENDMENT-phase
     * test whose {@code responseStatus} is {@code "FILLED_IN"} (a previously empty term was
     * populated).
     *
     * @param responses all responses for the run
     * @return counts keyed by {@code "<term>=<value>"}, with empty values rendered as
     *     {@code "<empty>"}
     */
    private static java.util.Map<String, Long> summarizeFilledInValues(List<Response> responses) {
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        responses.stream()
                .filter(response -> "FILLED_IN".equals(response.responseStatus()))
                .forEach(response -> response.amendments().forEach((term, amendedValue) -> counts.merge(
                        term + "=" + describeValue(amendedValue),
                        1L,
                        Long::sum)));
        return java.util.Map.copyOf(counts);
    }

    /**
     * Tallies how many times each {@code term: oldValue -> newValue} transition was produced by
     * an AMENDMENT-phase test whose {@code responseStatus} is {@code "AMENDED"} (an existing
     * term value was changed), pairing each amended value with the record's original value for
     * that term.
     *
     * @param responses all responses for the run
     * @param sourceTermsByRecordId each record's original term values, keyed by record ID
     * @return counts keyed by {@code "<term>: <old> -> <new>"}, with empty values rendered as
     *     {@code "<empty>"}
     */
    private static java.util.Map<String, Long> summarizeAmendedValuePairs(
            List<Response> responses,
            java.util.Map<String, java.util.Map<String, String>> sourceTermsByRecordId) {
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        responses.stream()
                .filter(response -> "AMENDED".equals(response.responseStatus()))
                .forEach(response -> {
                    java.util.Map<String, String> sourceTerms = sourceTermsByRecordId.getOrDefault(
                            response.recordId(),
                            java.util.Map.of());
                    response.amendments().forEach((term, amendedValue) -> counts.merge(
                            term + ": "
                                    + describeValue(sourceTerms.get(term))
                                    + " -> "
                                    + describeValue(amendedValue),
                            1L,
                            Long::sum));
                });
        return java.util.Map.copyOf(counts);
    }

    /**
     * Renders a term value for display, substituting a placeholder for null or blank values.
     *
     * @param value the raw value, possibly null or blank
     * @return {@code value}, or {@code "<empty>"} if it is null or blank
     */
    private static String describeValue(String value) {
        return value == null || value.isBlank() ? "<empty>" : value;
    }

    /**
     * Collects the distinct set of Darwin Core term names present across all records in the
     * dataset, preserving first-encountered order.
     *
     * @param dataset the ingested records to inspect
     * @return the distinct term names used by any record in the dataset
     */
    private static Set<String> collectAvailableTerms(org.filteredpush.bdq_workbench.model.RecordDataset dataset) {
        Set<String> terms = new LinkedHashSet<>();
        dataset.records().forEach(record -> terms.addAll(record.terms().keySet()));
        return terms;
    }
}
