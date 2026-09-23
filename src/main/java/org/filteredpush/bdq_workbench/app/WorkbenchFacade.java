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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.filteredpush.bdq_workbench.filtering.DefaultRecordFilterService;
import org.filteredpush.bdq_workbench.filtering.RecordFilterService;
import org.filteredpush.bdq_workbench.execution.TestExecutionService;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.ingest.IngestService;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
import org.filteredpush.bdq_workbench.model.ImplementationStatus;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.PreparedRun;
import org.filteredpush.bdq_workbench.model.RecordFilterSummary;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
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
    static final String OUTPUT_DIRECTORY = "reports";
    static final String BINDING_DIAGNOSTICS_FILE = "bdq-binding-diagnostics.txt";

    private final IngestService ingestService;
    private final PolicyResolverService policyResolverService;
    private final TestDiscoveryService testDiscoveryService;
    private final TestBindingService testBindingService;
    private final TestExecutionService executionService;
    private final ReportingService reportingService;
    private final RecordFilterService recordFilterService;

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
        this(
                ingestService,
                policyResolverService,
                testDiscoveryService,
                testBindingService,
                executionService,
                reportingService,
                new DefaultRecordFilterService());
    }

    /**
     * Creates a facade wired to the given pipeline and record-filtering services.
     *
     * @param ingestService service that ingests Darwin Core input into canonical records
     * @param policyResolverService service that resolves a use case identifier into an
     *     {@link ExecutionPlan} of executable tests
     * @param testDiscoveryService service that discovers available test implementations
     * @param testBindingService service that binds resolved tests to discovered implementations
     * @param executionService service that executes bound tests against canonical records
     * @param reportingService service that exports an {@link ExecutionSummary} to the
     *     configured report formats
     * @param recordFilterService service that applies pre-execution record filtering
     */
    public WorkbenchFacade(
            IngestService ingestService,
            PolicyResolverService policyResolverService,
            TestDiscoveryService testDiscoveryService,
            TestBindingService testBindingService,
            TestExecutionService executionService,
            ReportingService reportingService,
            RecordFilterService recordFilterService) {
        this.ingestService = ingestService;
        this.policyResolverService = policyResolverService;
        this.testDiscoveryService = testDiscoveryService;
        this.testBindingService = testBindingService;
        this.executionService = executionService;
        this.reportingService = reportingService;
        this.recordFilterService = recordFilterService;
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
        var ingestedDataset = ingestService.ingest(config.datasetPath(), config.datasetTable(), config.datasetView());
        RecordFilterSummary filterSummary = recordFilterService.apply(ingestedDataset, config.recordFilter());
        var dataset = filterSummary.filteredDataset();
        ExecutionPlan plan = policyResolverService.resolve(config.useCaseId());
        List<DiscoveredImplementation> discovered = testDiscoveryService.discover();
        TestBindingResult bindingResult = testBindingService.bind(
                plan.tests(),
                discovered,
                java.util.Map.of(),
                collectAvailableTerms(dataset));
        writeBindingDiagnosticsFile(plan, bindingResult);
        return new PreparedRun(config, dataset, plan, List.copyOf(discovered), bindingResult, filterSummary);
    }

    /**
     * Writes a developer-oriented binding diagnostics report when setup/preflight finds tests that
     * were not fully bound, and deletes any stale report when every binding is fully bound.
     *
     * @param plan the resolved execution plan for the current setup run
     * @param bindingResult the binding outcome produced during setup
     */
    private static void writeBindingDiagnosticsFile(ExecutionPlan plan, TestBindingResult bindingResult) {
        Path diagnosticsPath = bindingDiagnosticsPath();
        List<BindingReview> diagnosticReviews = bindingResult.reviews().stream()
                .filter(WorkbenchFacade::shouldWriteBindingDiagnostic)
                .sorted(bindingDiagnosticComparator())
                .toList();
        try {
            if (diagnosticReviews.isEmpty()) {
                Files.deleteIfExists(diagnosticsPath);
                return;
            }
            Files.createDirectories(diagnosticsPath.getParent());
            Files.writeString(
                    diagnosticsPath,
                    renderBindingDiagnostics(plan, diagnosticReviews),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AppException("Unable to write binding diagnostics", e);
        }
    }

    /**
     * Reports whether one binding review should appear in the developer diagnostics report.
     *
     * @param review one binding review
     * @return {@code true} when the review reflects an incomplete or failed binding
     */
    private static boolean shouldWriteBindingDiagnostic(BindingReview review) {
        return review.implementationStatus() != ImplementationStatus.FOUND
                || review.bindingStatus() != BindingStatus.BOUND
                || hasMissingInputTermDiagnostic(review);
    }

    /**
     * Orders diagnostics with binding/configuration errors first and missing-input-term problems
     * afterward, preserving a stable developer-friendly grouping.
     *
     * @return comparator for binding-diagnostics report rows
     */
    private static Comparator<BindingReview> bindingDiagnosticComparator() {
        return Comparator
                .comparingInt(WorkbenchFacade::bindingDiagnosticCategory)
                .thenComparing((BindingReview review) -> review.test().phase())
                .thenComparing(review -> review.test().id());
    }

    /**
     * Renders the plain-text binding diagnostics report written after setup/preflight.
     *
     * @param plan the execution plan whose tests were bound
     * @param diagnosticReviews the binding reviews to describe, already sorted
     * @return the diagnostics report text
     */
    private static String renderBindingDiagnostics(
            ExecutionPlan plan,
            List<BindingReview> diagnosticReviews) {
        StringBuilder builder = new StringBuilder();
        builder.append("BDQ Workbench binding diagnostics\n");
        builder.append("Use case: ").append(plan.useCase().id()).append(" (").append(plan.useCase().label()).append(")\n");
        builder.append("Problem summary\n");
        builder.append(" - Single-record test errors: ")
                .append(countBindingDiagnostics(diagnosticReviews, 0))
                .append('\n');
        builder.append(" - Single-record test binding problems: ")
                .append(countBindingDiagnostics(diagnosticReviews, 1))
                .append('\n');
        builder.append(" - Multi-record measure downstream errors/binding problems: ")
                .append(countBindingDiagnostics(diagnosticReviews, 2))
                .append('\n');
        builder.append(" - Missing input term problems: ")
                .append(countBindingDiagnostics(diagnosticReviews, 3))
                .append('\n');
        builder.append(" - Total diagnostics entries: ")
                .append(diagnosticReviews.size())
                .append("\n\n");
        builder.append("Entries are ordered with single-record test errors first, then single-record binding problems, then downstream multi-record measure issues, and finally missing-input-term problems.\n\n");
        appendBindingDiagnosticSection(builder, "Single-record test errors", diagnosticReviews, 0);
        appendBindingDiagnosticSection(builder, "Single-record test binding problems", diagnosticReviews, 1);
        appendBindingDiagnosticSection(builder, "Multi-record measure downstream errors/binding problems", diagnosticReviews, 2);
        appendBindingDiagnosticSection(builder, "Missing input term problems", diagnosticReviews, 3);
        return builder.toString();
    }

    /**
     * Appends one binding-diagnostics section.
     *
     * @param builder report buffer under construction
     * @param title section title
     * @param reviews all candidate reviews
     * @param category the diagnostics category to append
     */
    private static void appendBindingDiagnosticSection(
            StringBuilder builder,
            String title,
            List<BindingReview> reviews,
            int category) {
        List<BindingReview> sectionReviews = reviews.stream()
                .filter(review -> bindingDiagnosticCategory(review) == category)
                .toList();
        if (sectionReviews.isEmpty()) {
            return;
        }
        builder.append(title).append('\n');
        for (BindingReview review : sectionReviews) {
            builder.append(" - Test: ")
                    .append(review.test().id())
                    .append(" [")
                    .append(review.test().phase())
                    .append(", ")
                    .append(review.test().type())
                    .append("]\n");
            if (review.test().label() != null && !review.test().label().isBlank()) {
                builder.append("   Label: ").append(review.test().label()).append('\n');
            }
            if (review.chosenImplementationMethod() != null && !review.chosenImplementationMethod().isBlank()) {
                builder.append("   Candidate: ").append(review.chosenImplementationMethod()).append('\n');
            }
            builder.append("   Implementation status: ").append(review.implementationStatus()).append('\n');
            builder.append("   Binding status: ").append(review.bindingStatus()).append('\n');
            builder.append("   Developer explanation: ").append(bindingDeveloperExplanation(review)).append('\n');
            if (!review.parameterValues().isEmpty()) {
                builder.append("   Parameter values: ").append(review.parameterValues()).append('\n');
            }
            builder.append("   Diagnostics:\n");
            review.diagnostics().forEach(diagnostic -> builder.append("    * ").append(diagnostic).append('\n'));
        }
        builder.append('\n');
    }

    /**
     * Counts how many diagnostics reviews fall into one category.
     *
     * @param reviews the diagnostic reviews to count
     * @param category the category identifier returned by {@link #bindingDiagnosticCategory}
     * @return the number of reviews in that category
     */
    private static long countBindingDiagnostics(List<BindingReview> reviews, int category) {
        return reviews.stream()
                .filter(review -> bindingDiagnosticCategory(review) == category)
                .count();
    }

    /**
     * Classifies one binding diagnostic into its report ordering bucket.
     *
     * @param review the binding review to classify
     * @return 0 for single-record errors, 1 for single-record binding problems, 2 for multi-record
     *     measure downstream problems, 3 for missing-input-term problems
     */
    private static int bindingDiagnosticCategory(BindingReview review) {
        if (review.bindingStatus() == BindingStatus.TERM_MISSING || hasMissingInputTermDiagnostic(review)) {
            return 3;
        }
        if (isMultiRecordMeasure(review)) {
            return 2;
        }
        if (review.implementationStatus() != ImplementationStatus.FOUND) {
            return 0;
        }
        return 1;
    }

    /**
     * Reports whether a binding review describes a multi-record measure.
     *
     * @param review the binding review to inspect
     * @return {@code true} for multi-record measure tests, {@code false} otherwise
     */
    private static boolean isMultiRecordMeasure(BindingReview review) {
        return review.test().type() == TestType.MEASURE
                || (review.test().label() != null && review.test().label().startsWith("MULTIRECORD_"));
    }

    /**
     * Reports whether a review carries a warning that one of its information-element terms was
     * absent from the dataset and therefore bound as an empty string.
     *
     * @param review the binding review to inspect
     * @return {@code true} if the review diagnostics include the empty-string missing-term warning
     */
    private static boolean hasMissingInputTermDiagnostic(BindingReview review) {
        return review.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.startsWith("Term acted_upon/consulted absent in input data:"));
    }

    /**
     * Summarizes the likely cause of one incomplete binding in developer-facing terms.
     *
     * @param review the binding review to explain
     * @return one concise developer-oriented explanation
     */
    private static String bindingDeveloperExplanation(BindingReview review) {
        if (review.bindingStatus() == BindingStatus.TERM_MISSING || hasMissingInputTermDiagnostic(review)) {
            return "One or more Darwin Core information elements were absent from the filtered dataset. This binding will execute with empty-string values for those terms, so review the dataset table selection, record filters, ingest mapping, and whether the test expects fields carried only in another table.";
        }
        if (review.implementationStatus() == ImplementationStatus.MISSING) {
            return "No discovered implementation matched this policy test. Verify the dependency is on the classpath, the discovery package includes it, and its @Provides/@ProvidesVersion identifiers match the RDF test definition.";
        }
        if (review.implementationStatus() == ImplementationStatus.AMBIGUOUS) {
            return "More than one discovered implementation remained viable for this test. Compare the candidate signatures and annotations, then either fix the conflicting metadata or supply an explicit full-signature mapping.";
        }
        if (review.bindingStatus() == BindingStatus.UNBOUND) {
            return "A candidate implementation was discovered but could not be bound safely. Review the parameter diagnostics for missing RDF parameters, namespace mismatches, overload ambiguity, or stale implementation metadata.";
        }
        if (review.bindingStatus() == BindingStatus.PARTIAL) {
            return "This binding is only partial: execution may still be possible, but at least one optional/defaultable input could not be matched exactly. Review the diagnostics to confirm the implementation defaults and parameter mapping are intentional.";
        }
        return "Review the diagnostics below for the exact binding mismatch.";
    }

    /**
     * Returns the setup-phase binding diagnostics output path.
     *
     * @return the binding diagnostics file under the standard output directory
     */
    static Path bindingDiagnosticsPath() {
        return Path.of(System.getProperty("user.dir"), OUTPUT_DIRECTORY, BINDING_DIAGNOSTICS_FILE);
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
                bindingResult.runnableBindings().size(),
                discovered.size());

        List<Response> responses = new ArrayList<>(executionService.execute(
                dataset,
                bindingResult.runnableBindings(),
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
                .thenComparing(Response::recordId)
                .thenComparing(response -> response.derived() ? 1 : 0)
                .thenComparing(response -> response.subjectRef() == null ? "" : response.subjectRef().sortKey()));
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
        java.util.Map<String, String> testLabelsById = new java.util.LinkedHashMap<>();
        preparedRun.plan().tests().forEach(test -> testLabelsById.put(test.id(), test.label()));
        preparedRun.plan().unresolvedTests().forEach(test -> testLabelsById.put(test.id(), test.label()));
        preparedRun.bindingResult().unresolved().forEach(test -> testLabelsById.putIfAbsent(test.id(), test.label()));
        return new ExecutionSummaryMetadata(
                useCase.id(),
                useCase.label(),
                preparedRun.config() == null || preparedRun.config().datasetPath() == null
                        ? ""
                        : preparedRun.config().datasetPath().toString(),
                preparedRun.filterSummary().originalDarwinCoreTermCount(),
                preparedRun.filterSummary().originalRecordCount(),
                preparedRun.filterSummary().filteredDarwinCoreTermCount(),
                preparedRun.filterSummary().filteredRecordCount(),
                preparedRun.filterSummary().resolvedCriteria(),
                testLabelsById,
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
        dataset.recordGraphs().forEach(graph -> graph.relatedByRelation().values().forEach(related ->
                related.forEach(record -> terms.addAll(record.terms().keySet()))));
        return terms;
    }
}
