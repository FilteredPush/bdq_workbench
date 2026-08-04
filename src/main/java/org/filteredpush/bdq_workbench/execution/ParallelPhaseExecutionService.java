/** ParallelPhaseExecutionService.java
 *
 * TestExecutionService implementation that executes bound tests phase by phase using a fixed-size thread pool, applying amendments between phases and synthesizing built-in measure responses.
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
package org.filteredpush.bdq_workbench.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TestExecutionService} implementation that executes policy bindings in
 * PRE_AMENDMENT/AMENDMENT/POST_AMENDMENT phases, in that order, with deterministic response
 * ordering.
 *
 * <p>Within a phase, this service submits one {@link ExecutionAdapter#execute} invocation per
 * record/binding pair to a fixed-size {@link ExecutorService}, then collects the resulting
 * {@link Future}s in submission order (not completion order) so that any exception is attributed
 * to the correct record/binding and, for the AMENDMENT phase, so amendments are applied to the
 * dataset in a stable order. The PRE_AMENDMENT phase runs against an immutable copy of the input
 * dataset; the AMENDMENT and POST_AMENDMENT phases share a second copy, into which each
 * AMENDMENT-phase response's term amendments are merged (via {@link #applyAmendments}) before the
 * POST_AMENDMENT phase begins, so validation/measure tests in POST_AMENDMENT observe amended term
 * values. {@link #bindingsForPhase} additionally re-binds non-amendment PRE_AMENDMENT bindings
 * (validations and measures without an explicit POST_AMENDMENT binding) into the POST_AMENDMENT
 * phase, so that such tests are evaluated against post-amendment data by default.
 *
 * <p>Bindings identified by {@link BuiltInMeasureSpec#isBuiltIn} (synthetic COMPLETENESS/COUNT
 * measures with no real implementation to invoke) are not submitted to the executor; instead
 * {@link #synthesizeBuiltInMeasure} computes their result directly from the other responses
 * already produced in the same phase, once their target test's direct bindings have also run in
 * that phase.
 *
 * <p>Execution progress is reported via the configured {@link ExecutionProgressListener}, and any
 * unhandled exception from a submitted task, or a built-in measure synthesis failure, is caught
 * and converted into an {@link OutcomeStatus#ERROR} response rather than aborting the phase.
 */
public class ParallelPhaseExecutionService implements TestExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(ParallelPhaseExecutionService.class);
    private final int threadCount;
    private final ExecutionAdapter executionAdapter;
    private final ExecutionProgressListener progressListener;

    /**
     * Creates a service with no progress reporting (an {@link ExecutionProgressListener} with all
     * default no-op callbacks).
     *
     * @param threadCount the number of worker threads to use per phase; values less than 1 are
     *     treated as 1
     * @param executionAdapter the adapter used to invoke each binding against each record
     */
    public ParallelPhaseExecutionService(int threadCount, ExecutionAdapter executionAdapter) {
        this(threadCount, executionAdapter, new ExecutionProgressListener() {
        });
    }

    /**
     * Creates a service that reports progress to the given listener.
     *
     * @param threadCount the number of worker threads to use per phase; values less than 1 are
     *     treated as 1
     * @param executionAdapter the adapter used to invoke each binding against each record
     * @param progressListener the listener notified as each phase starts, as each response is
     *     produced, and as each phase completes
     */
    public ParallelPhaseExecutionService(
            int threadCount,
            ExecutionAdapter executionAdapter,
            ExecutionProgressListener progressListener) {
        this.threadCount = Math.max(1, threadCount);
        this.executionAdapter = executionAdapter;
        this.progressListener = progressListener;
    }

    /**
     * Executes the given bindings against the dataset's records in three phases — PRE_AMENDMENT,
     * AMENDMENT, then POST_AMENDMENT — applying any amendments produced in the AMENDMENT phase to
     * the dataset before the POST_AMENDMENT phase runs.
     *
     * @param dataset the records to execute the bound tests against; this instance is not
     *     mutated, since each phase operates on its own {@link RecordDataset#copy()}
     * @param bindings the resolved test-to-implementation bindings to execute, spanning all three
     *     phases
     * @param discovered the discovered implementations referenced by the bindings, looked up by
     *     implementation class and method name
     * @return all responses from all three phases (including any synthesized built-in measure
     *     responses), sorted by phase, then test ID, then record ID, then implementation class
     *     and method
     */
    @Override
    public List<Response> execute(
            RecordDataset dataset,
            List<ImplementationBinding> bindings,
            List<DiscoveredImplementation> discovered) {
        Map<String, DiscoveredImplementation> discoveredByKey = new ConcurrentHashMap<>();
        discovered.forEach(d -> discoveredByKey.put(d.implementationClass() + "#" + d.implementationMethod(), d));

        List<Response> results = new ArrayList<>();
        RecordDataset immutableSource = dataset.copy();
        RecordDataset amendmentCopy = dataset.copy();

        results.addAll(executePhase(Phase.PRE_AMENDMENT, immutableSource, bindingsForPhase(Phase.PRE_AMENDMENT, bindings), discoveredByKey));
        results.addAll(executePhase(Phase.AMENDMENT, amendmentCopy, bindingsForPhase(Phase.AMENDMENT, bindings), discoveredByKey));
        results.addAll(executePhase(Phase.POST_AMENDMENT, amendmentCopy, bindingsForPhase(Phase.POST_AMENDMENT, bindings), discoveredByKey));

        results.sort(Comparator
                .comparing(Response::phase)
                .thenComparing(Response::testId)
                .thenComparing(Response::recordId)
                .thenComparing(Response::implementationClass)
                .thenComparing(Response::implementationMethod));
        return results;
    }

    /**
     * Executes a single phase: submits one {@link ExecutionAdapter#execute} call per
     * record/binding pair (for non-built-in bindings) to a fresh fixed-size thread pool, collects
     * results in submission order, applies amendments to {@code dataset} as AMENDMENT-phase
     * responses arrive, then synthesizes responses for any built-in measure bindings whose target
     * test also ran directly in this phase.
     *
     * @param phase the phase being executed, used for logging, progress reporting, and to decide
     *     whether to apply amendments and whether built-in measures are eligible
     * @param dataset the dataset to execute against (and, for the AMENDMENT phase, to apply
     *     amendments to) for this phase
     * @param bindings the bindings applicable to this phase, as produced by
     *     {@link #bindingsForPhase}
     * @param discoveredByKey discovered implementations keyed by
     *     {@code "<implementationClass>#<implementationMethod>"}
     * @return the responses produced in this phase, in the order completed (direct invocations
     *     followed by synthesized built-in measures); empty if there is no work to do
     * @throws RuntimeException if the executor is interrupted while awaiting results, or if an
     *     unexpected (non-{@link ExecutionException}) failure occurs while collecting results
     */
    private List<Response> executePhase(
            Phase phase,
            RecordDataset dataset,
            List<ImplementationBinding> bindings,
            Map<String, DiscoveredImplementation> discoveredByKey) {
        List<ImplementationBinding> phaseBindings = bindings.stream()
                .filter(binding -> !BuiltInMeasureSpec.isBuiltIn(binding))
                .toList();
        List<ImplementationBinding> builtInMeasures = phase == Phase.AMENDMENT
                ? List.of()
                : bindings.stream()
                        .filter(BuiltInMeasureSpec::isBuiltIn)
                        .filter(binding -> hasTargetBindingForPhase(binding, phaseBindings))
                        .toList();
        if (phaseBindings.isEmpty() && builtInMeasures.isEmpty()) {
            return List.of();
        }
        int total = dataset.records().size() * phaseBindings.size() + builtInMeasures.size();
        LOG.debug("Starting phase {} with {} records, {} direct bindings, {} built-in measures",
                phase, dataset.records().size(), phaseBindings.size(), builtInMeasures.size());
        progressListener.onPhaseStarted(phase, total);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<PendingExecution> futures = new ArrayList<>();
            for (var record : dataset.records()) {
                for (var binding : phaseBindings) {
                    String implementationKey = binding.implementationClass() + "#" + binding.implementationMethod();
                    futures.add(new PendingExecution(
                            record.id(),
                            binding,
                            executor.submit(() -> {
                                progressListener.onTaskStarted(phase);
                                try {
                                    return executionAdapter.execute(
                                            record,
                                            binding,
                                            discoveredByKey.get(implementationKey));
                                } finally {
                                    progressListener.onTaskFinished(phase);
                                }
                            })));
                }
            }
            List<Response> responses = new ArrayList<>();
            int completed = 0;
            for (PendingExecution pending : futures) {
                Response response;
                try {
                    response = pending.future().get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    LOG.error("Unhandled execution failure in phase {} for test {} using {}.{} on record {}: {}",
                            phase,
                            pending.binding().testId(),
                            pending.binding().implementationClass(),
                            pending.binding().implementationMethod(),
                            pending.recordId(),
                            cause.getMessage(),
                            cause);
                    response = errorResponse(pending.recordId(), pending.binding(), cause);
                }
                responses.add(response);
                completed++;
                if (phase == Phase.AMENDMENT) {
                    applyAmendments(dataset, response);
                }
                progressListener.onResponse(phase, response, completed, total);
            }
            for (ImplementationBinding measureBinding : builtInMeasures) {
                Response response;
                try {
                    response = synthesizeBuiltInMeasure(phase, dataset, measureBinding, responses);
                } catch (RuntimeException e) {
                    LOG.error("Built-in measure execution failed in phase {} for test {}: {}",
                            phase, measureBinding.testId(), e.getMessage(), e);
                    response = errorResponse("MULTIRECORD", measureBinding, e);
                }
                responses.add(response);
                completed++;
                progressListener.onResponse(phase, response, completed, total);
            }
            progressListener.onPhaseCompleted(phase, completed, total);
            LOG.debug("Completed phase {} with {} responses", phase, completed);
            return responses;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Execution interrupted in phase {}", phase, e);
            throw new RuntimeException("Execution interrupted in phase " + phase, e);
        } catch (Exception e) {
            LOG.error("Execution failed in phase {} with {} records, {} direct bindings, {} built-in measures",
                    phase, dataset.records().size(), phaseBindings.size(), builtInMeasures.size(), e);
            throw new RuntimeException("Execution failed in phase " + phase, e);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Selects and, where needed, re-phases the bindings applicable to {@code phase}.
     *
     * <p>For PRE_AMENDMENT and AMENDMENT, this simply filters bindings whose own
     * {@link ImplementationBinding#phase()} matches. For POST_AMENDMENT, it returns the union of
     * bindings explicitly bound to POST_AMENDMENT and any non-amendment (validation/measure)
     * PRE_AMENDMENT bindings whose test ID has no explicit POST_AMENDMENT binding — those are
     * copied with their phase changed to POST_AMENDMENT via {@link #copyWithPhase} — so that
     * validations and measures run against post-amendment data by default unless a use case
     * explicitly re-binds them to PRE_AMENDMENT.
     *
     * @param phase the phase to select bindings for
     * @param bindings all bindings for the run, across all phases
     * @return the bindings to execute for {@code phase}
     */
    private static List<ImplementationBinding> bindingsForPhase(Phase phase, List<ImplementationBinding> bindings) {
        if (phase == Phase.AMENDMENT) {
            return bindings.stream()
                    .filter(binding -> binding.phase() == Phase.AMENDMENT)
                    .toList();
        }
        if (phase == Phase.PRE_AMENDMENT) {
            return bindings.stream()
                    .filter(binding -> binding.phase() == Phase.PRE_AMENDMENT)
                    .toList();
        }
        Set<String> explicitPostTestIds = bindings.stream()
                .filter(binding -> binding.phase() == Phase.POST_AMENDMENT)
                .map(ImplementationBinding::testId)
                .collect(java.util.stream.Collectors.toSet());
        List<ImplementationBinding> reboundPreBindings = bindings.stream()
                .filter(binding -> binding.phase() == Phase.PRE_AMENDMENT)
                .filter(binding -> binding.testType() != TestType.AMENDMENT)
                .filter(binding -> !explicitPostTestIds.contains(binding.testId()))
                .map(binding -> copyWithPhase(binding, Phase.POST_AMENDMENT))
                .toList();
        List<ImplementationBinding> explicitPost = bindings.stream()
                .filter(binding -> binding.phase() == Phase.POST_AMENDMENT)
                .toList();
        List<ImplementationBinding> merged = new ArrayList<>(explicitPost);
        merged.addAll(reboundPreBindings);
        return List.copyOf(merged);
    }

    /**
     * Returns a copy of {@code binding} with its {@link ImplementationBinding#phase()} replaced,
     * all other fields unchanged.
     *
     * @param binding the binding to copy
     * @param phase the phase to assign to the copy
     * @return a new binding identical to {@code binding} except for its phase
     */
    private static ImplementationBinding copyWithPhase(ImplementationBinding binding, Phase phase) {
        return new ImplementationBinding(
                binding.testId(),
                binding.testType(),
                binding.implementationClass(),
                binding.implementationMethod(),
                phase,
                binding.parameters(),
                binding.bindingStatus(),
                binding.parameterizationCapability(),
                binding.methodSelection(),
                binding.usingDefaultParameters(),
                binding.parameterBindings(),
                binding.diagnostics());
    }

    /**
     * Determines whether a built-in measure binding is eligible to run in the current phase, by
     * checking whether its target test has a matching (non-built-in) direct binding among
     * {@code directPhaseBindings}.
     *
     * @param measureBinding the built-in measure binding to check
     * @param directPhaseBindings the non-built-in bindings already selected for this phase
     * @return {@code true} if {@code measureBinding} identifies a valid
     *     {@link BuiltInMeasureSpec} and that spec's target test ID matches one of
     *     {@code directPhaseBindings}; {@code false} otherwise
     */
    private static boolean hasTargetBindingForPhase(
            ImplementationBinding measureBinding,
            List<ImplementationBinding> directPhaseBindings) {
        return BuiltInMeasureSpec.from(measureBinding)
                .map(spec -> directPhaseBindings.stream()
                        .filter(binding -> !BuiltInMeasureSpec.isBuiltIn(binding))
                        .anyMatch(binding -> spec.targetTestId().equals(binding.testId())))
                .orElse(false);
    }

    /**
     * Computes the response for a built-in measure binding from the other responses already
     * produced in this phase, dispatching to {@link #synthesizeCountMeasure} or
     * {@link #synthesizeQaMeasure} depending on the measure's {@link BuiltInMeasureSpec.MeasureKind}.
     *
     * @param phase the phase the measure belongs to
     * @param dataset the phase's dataset, used for its total record count
     * @param measureBinding the built-in measure binding to synthesize a response for
     * @param phaseResponses the responses already produced for the target test in this phase
     * @return the synthesized measure response
     * @throws IllegalArgumentException if {@code measureBinding} does not identify a valid
     *     {@link BuiltInMeasureSpec}
     */
    private static Response synthesizeBuiltInMeasure(
            Phase phase,
            RecordDataset dataset,
            ImplementationBinding measureBinding,
            List<Response> phaseResponses) {
        BuiltInMeasureSpec spec = BuiltInMeasureSpec.from(measureBinding)
                .orElseThrow(() -> new IllegalArgumentException("Not a built-in measure binding: " + measureBinding.testId()));
        java.time.Instant finishedAt = java.time.Instant.now();
        return spec.kind() == BuiltInMeasureSpec.MeasureKind.COUNT
                ? synthesizeCountMeasure(phase, measureBinding, spec, phaseResponses, dataset.records().size(), finishedAt)
                : synthesizeQaMeasure(phase, measureBinding, spec, phaseResponses, dataset.records().size(), finishedAt);
    }

    /**
     * Synthesizes a COUNT-kind built-in measure response, tallying how many of the target test's
     * responses in this phase matched the spec's expected {@code responseResult}.
     *
     * @param phase the phase the measure belongs to
     * @param measureBinding the built-in measure binding being synthesized
     * @param spec the measure's specification (target test, expected result, kind)
     * @param phaseResponses the responses already produced for the target test in this phase
     * @param totalRecords the total number of records in the dataset, used as the count
     *     denominator
     * @param finishedAt the timestamp to record as both the start and finish time of this
     *     synthesized response
     * @return a {@link OutcomeStatus#PASSED} response reporting the matching count, total, and
     *     percentage
     */
    private static Response synthesizeCountMeasure(
            Phase phase,
            ImplementationBinding measureBinding,
            BuiltInMeasureSpec spec,
            List<Response> phaseResponses,
            int totalRecords,
            java.time.Instant finishedAt) {
        long matchingCount = phaseResponses.stream()
                .filter(response -> spec.targetTestId().equals(response.testId()))
                .filter(response -> spec.responseResult().equals(response.responseResult()))
                .count();
        double percentage = totalRecords == 0 ? 0.0d : (matchingCount * 100.0d) / totalRecords;
        String message = String.format(
                "%d/%d records matched %s for %s (%.1f%%)",
                matchingCount,
                totalRecords,
                spec.responseResult(),
                spec.targetTestLabel(),
                percentage);
        Map<String, String> parameters = new LinkedHashMap<>(measureBinding.parameters());
        parameters.put(BuiltInMeasureSpec.MATCHING_COUNT_KEY, Long.toString(matchingCount));
        parameters.put(BuiltInMeasureSpec.TOTAL_RECORDS_KEY, Integer.toString(totalRecords));
        parameters.put(BuiltInMeasureSpec.PERCENTAGE_KEY, String.format("%.1f", percentage));
        return new Response(
                "MULTIRECORD",
                measureBinding.testId(),
                measureBinding.testType(),
                measureBinding.implementationClass(),
                measureBinding.implementationMethod(),
                phase,
                Map.copyOf(parameters),
                OutcomeStatus.PASSED,
                "RUN_HAS_RESULT",
                Long.toString(matchingCount),
                message,
                message,
                Map.of(),
                finishedAt,
                finishedAt);
    }

    /**
     * Synthesizes a QA (COMPLETENESS)-kind built-in measure response, checking whether every
     * eligible response for the target test in this phase satisfied the measure's QA condition.
     *
     * @param phase the phase the measure belongs to
     * @param measureBinding the built-in measure binding being synthesized
     * @param spec the measure's specification (target test, QA condition, kind)
     * @param phaseResponses the responses already produced for the target test in this phase
     * @param totalRecords the total number of records in the dataset, used (together with the
     *     eligible count) as the reported denominator
     * @param finishedAt the timestamp to record as both the start and finish time of this
     *     synthesized response
     * @return a response whose outcome is {@link OutcomeStatus#PASSED} with result
     *     {@code "COMPLETE"} if every eligible response matched the QA condition, or
     *     {@link OutcomeStatus#FAILED} with result {@code "NOT_COMPLETE"} otherwise
     */
    private static Response synthesizeQaMeasure(
            Phase phase,
            ImplementationBinding measureBinding,
            BuiltInMeasureSpec spec,
            List<Response> phaseResponses,
            int totalRecords,
            java.time.Instant finishedAt) {
        long eligibleCount = phaseResponses.stream()
                .filter(response -> spec.targetTestId().equals(response.testId()))
                .count();
        long matchingCount = phaseResponses.stream()
                .filter(response -> spec.targetTestId().equals(response.testId()))
                .filter(spec::matchesQaCondition)
                .count();
        boolean complete = eligibleCount == matchingCount;
        String responseResult = complete ? "COMPLETE" : "NOT_COMPLETE";
        String message = complete
                ? String.format(
                        "%s for %s: %d/%d responses satisfied the QA criteria",
                        responseResult,
                        spec.targetTestLabel(),
                        matchingCount,
                        Math.max(totalRecords, (int) eligibleCount))
                : String.format(
                        "%s for %s: %d/%d responses satisfied the QA criteria",
                        responseResult,
                        spec.targetTestLabel(),
                        matchingCount,
                        Math.max(totalRecords, (int) eligibleCount));
        Map<String, String> parameters = new LinkedHashMap<>(measureBinding.parameters());
        parameters.put(BuiltInMeasureSpec.MATCHING_COUNT_KEY, Long.toString(matchingCount));
        parameters.put(BuiltInMeasureSpec.TOTAL_RECORDS_KEY, Integer.toString(totalRecords));
        parameters.put(BuiltInMeasureSpec.PERCENTAGE_KEY, totalRecords == 0
                ? "0.0"
                : String.format("%.1f", matchingCount * 100.0d / totalRecords));
        return new Response(
                "MULTIRECORD",
                measureBinding.testId(),
                measureBinding.testType(),
                measureBinding.implementationClass(),
                measureBinding.implementationMethod(),
                phase,
                Map.copyOf(parameters),
                complete ? OutcomeStatus.PASSED : OutcomeStatus.FAILED,
                "RUN_HAS_RESULT",
                responseResult,
                message,
                message,
                Map.of(),
                finishedAt,
                finishedAt);
    }

    /**
     * Merges an AMENDMENT-phase response's amendments into the matching record's term map in
     * {@code dataset}, so that later bindings in the same (or a subsequent) phase observe the
     * amended values. Does nothing if the response carries no amendments or if no record with a
     * matching ID is found.
     *
     * @param dataset the dataset whose matching record's terms are updated in place
     * @param response the response whose {@link Response#amendments()} are to be applied
     */
    private static void applyAmendments(RecordDataset dataset, Response response) {
        if (response.amendments().isEmpty()) {
            return;
        }
        LOG.debug("Applying amendments for record {}: {}", response.recordId(), response.amendments());
        dataset.records().stream()
                .filter(record -> record.id().equals(response.recordId()))
                .findFirst()
                .ifPresent(record -> response.amendments().forEach(record.terms()::put));
    }

    /**
     * Builds an {@link OutcomeStatus#ERROR} response for a binding invocation that failed with an
     * unhandled exception.
     *
     * @param recordId the ID of the record the invocation was attempted against
     * @param binding the binding that failed
     * @param error the failure that occurred
     * @return an error response describing the failure
     */
    private static Response errorResponse(String recordId, ImplementationBinding binding, Throwable error) {
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName()
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        java.time.Instant now = java.time.Instant.now();
        return new Response(
                recordId,
                binding.testId(),
                binding.testType(),
                binding.implementationClass(),
                binding.implementationMethod(),
                binding.phase(),
                binding.parameters(),
                OutcomeStatus.ERROR,
                "ERROR",
                null,
                message,
                message,
                Map.of(),
                now,
                now);
    }

    /**
     * A submitted invocation awaiting completion, paired with the record and binding it was
     * submitted for so that failures and amendments can be attributed correctly once the future
     * completes.
     *
     * @param recordId the ID of the record the invocation was submitted for
     * @param binding the binding the invocation was submitted for
     * @param future the pending result of the invocation
     */
    private record PendingExecution(String recordId, ImplementationBinding binding, Future<Response> future) {
    }
}
