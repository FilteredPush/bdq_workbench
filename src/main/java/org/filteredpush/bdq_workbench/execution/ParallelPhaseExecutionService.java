/** ParallelPhaseExecutionService.java
 *
 * TestExecutionService implementation that executes bound tests phase by phase using a fixed-size thread pool, invoking each test once per distinct combination of its declared input values rather than once per record, applying amendments between phases, and synthesizing built-in measure responses.
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
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
 * <p><b>Distinct-value execution.</b> Within a phase, a test is a pure function of the Darwin
 * Core terms it declares as {@code ACTED_UPON}/{@code CONSULTED} input (see
 * {@link org.filteredpush.bdq_workbench.model.ParameterRole}), so records sharing identical values
 * for exactly those terms must produce identical results. Rather than invoking
 * {@link ExecutionAdapter#execute} once per record/binding pair, this service partitions the
 * phase's records into distinct-value groups per binding (via {@link RecordGroupPartitioner}),
 * invokes the binding once against one representative record per group, and then copies that one
 * {@link Response} to every other record in the group. The resulting response list has exactly the
 * same shape as invoking per-record would have produced — one response per record per binding —
 * just with fewer real invocations. Two bindings that happen to declare the same set of term names
 * (regardless of test type, or whether a term is {@code ACTED_UPON} for one and {@code CONSULTED}
 * for the other) share the same partitioning work via a {@link PhaseGroupCache} scoped to the
 * phase, rather than each recomputing it. Bindings with any {@code LEGACY_RECORD}/
 * {@code LEGACY_PARAMETERS} parameter (whose implementation reads the whole record or parameter
 * map, not specific declared terms) are not eligible for this and always run once per record, as
 * is every binding when {@link #dedupEnabled} is {@code false}.
 *
 * <p><b>Amendment sequencing.</b> PRE_AMENDMENT and POST_AMENDMENT never mutate records mid-phase
 * (amendments are only ever applied for the AMENDMENT phase), so every eligible binding's groups
 * for either of those phases are computed and submitted together, and the resulting responses are
 * collected in submission order. The AMENDMENT phase is different: one binding's amendment can
 * change term values a later binding in the same phase groups or reads by, so AMENDMENT-phase
 * bindings are processed one at a time — each binding's groups are computed, invoked, fanned out
 * to every group member, and its resulting amendments applied to the dataset, before the next
 * binding's groups are computed. Any cached partition whose field set overlaps the fields just
 * changed is discarded (via {@link PhaseGroupCache#invalidate}) so the next binding needing one of
 * those fields recomputes it from the now-amended values; partitions for unrelated field sets stay
 * cached and shared. The PRE_AMENDMENT phase runs against an immutable copy of the input dataset;
 * the AMENDMENT and POST_AMENDMENT phases share a second copy, so that validation/measure tests in
 * POST_AMENDMENT observe amended term values. {@link #bindingsForPhase} additionally re-binds
 * non-amendment PRE_AMENDMENT bindings (validations and measures without an explicit
 * POST_AMENDMENT binding) into the POST_AMENDMENT phase, so that such tests are evaluated against
 * post-amendment data by default.
 *
 * <p>Bindings identified by {@link BuiltInMeasureSpec#isBuiltIn} (synthetic COMPLETENESS/COUNT
 * measures with no real implementation to invoke) are not submitted to the executor at all (grouped
 * or otherwise); instead {@link #synthesizeBuiltInMeasure} computes their result directly from the
 * other responses already produced in the same phase, once their target test's direct bindings
 * have also run in that phase.
 *
 * <p>Execution progress is reported via the configured {@link ExecutionProgressListener}:
 * {@link ExecutionProgressListener#onTaskStarted}/{@link ExecutionProgressListener#onTaskFinished}
 * bracket each actual invocation (i.e. once per distinct group, not once per record — they
 * genuinely track concurrent work happening, which distinct-value execution reduces), while
 * {@link ExecutionProgressListener#onResponse} and the {@code total} passed to
 * {@link ExecutionProgressListener#onPhaseStarted}/{@link ExecutionProgressListener#onPhaseCompleted}
 * count responses (one per record per binding, plus built-in measures) exactly as before, since
 * that is what represents how much of the input data has been evaluated. Any unhandled exception
 * from a submitted task, or a built-in measure synthesis failure, is caught and converted into an
 * {@link OutcomeStatus#ERROR} response (fanned out to every member of the group that failed, since
 * they would have failed identically) rather than aborting the phase.
 */
public class ParallelPhaseExecutionService implements TestExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(ParallelPhaseExecutionService.class);
    private final int threadCount;
    private final ExecutionAdapter executionAdapter;
    private final ExecutionProgressListener progressListener;
    private final boolean dedupEnabled;

    /**
     * Creates a service with no progress reporting (an {@link ExecutionProgressListener} with all
     * default no-op callbacks) and distinct-value execution enabled.
     *
     * @param threadCount the number of worker threads to use per phase; values less than 1 are
     *     treated as 1
     * @param executionAdapter the adapter used to invoke each binding against each record
     */
    public ParallelPhaseExecutionService(int threadCount, ExecutionAdapter executionAdapter) {
        this(threadCount, executionAdapter, new ExecutionProgressListener() {
        }, true);
    }

    /**
     * Creates a service with no progress reporting and the given distinct-value execution setting.
     *
     * @param threadCount the number of worker threads to use per phase; values less than 1 are
     *     treated as 1
     * @param executionAdapter the adapter used to invoke each binding against each record
     * @param dedupEnabled whether to invoke each eligible binding once per distinct combination of
     *     its declared input values rather than once per record
     */
    public ParallelPhaseExecutionService(int threadCount, ExecutionAdapter executionAdapter, boolean dedupEnabled) {
        this(threadCount, executionAdapter, new ExecutionProgressListener() {
        }, dedupEnabled);
    }

    /**
     * Creates a service that reports progress to the given listener, with distinct-value execution
     * enabled.
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
        this(threadCount, executionAdapter, progressListener, true);
    }

    /**
     * Creates a service that reports progress to the given listener, with the given distinct-value
     * execution setting.
     *
     * @param threadCount the number of worker threads to use per phase; values less than 1 are
     *     treated as 1
     * @param executionAdapter the adapter used to invoke each binding against each record
     * @param progressListener the listener notified as each phase starts, as each response is
     *     produced, and as each phase completes
     * @param dedupEnabled whether to invoke each eligible binding once per distinct combination of
     *     its declared input values rather than once per record; when {@code false}, every
     *     binding runs once per record exactly as if none were dedup-eligible
     */
    public ParallelPhaseExecutionService(
            int threadCount,
            ExecutionAdapter executionAdapter,
            ExecutionProgressListener progressListener,
            boolean dedupEnabled) {
        this.threadCount = Math.max(1, threadCount);
        this.executionAdapter = executionAdapter;
        this.progressListener = progressListener;
        this.dedupEnabled = dedupEnabled;
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
     * Executes a single phase: for each non-built-in binding, partitions the phase's records into
     * distinct-value groups (or one group per record, if the binding is not dedup-eligible or
     * {@link #dedupEnabled} is {@code false}) and submits one {@link ExecutionAdapter#execute} call
     * per group to a fresh fixed-size thread pool, then fans each group's resulting response out to
     * every record in the group. For the AMENDMENT phase, bindings are processed one at a time (see
     * the class Javadoc); for PRE_AMENDMENT/POST_AMENDMENT, every binding's groups are submitted
     * together. Finally synthesizes responses for any built-in measure bindings whose target test
     * also ran directly in this phase.
     *
     * @param phase the phase being executed, used for logging, progress reporting, and to decide
     *     whether to apply amendments and whether built-in measures are eligible
     * @param dataset the dataset to execute against (and, for the AMENDMENT phase, to apply
     *     amendments to) for this phase
     * @param bindings the bindings applicable to this phase, as produced by
     *     {@link #bindingsForPhase}
     * @param discoveredByKey discovered implementations keyed by
     *     {@code "<implementationClass>#<implementationMethod>"}
     * @return the responses produced in this phase (direct invocations, fanned out per record,
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
            Map<String, CanonicalRecord> recordsById = new LinkedHashMap<>();
            dataset.records().forEach(record -> recordsById.put(record.id(), record));
            PhaseGroupCache groupCache = new PhaseGroupCache(dataset.records());

            List<Response> responses = new ArrayList<>();
            int completed = 0;
            if (phase == Phase.AMENDMENT) {
                for (ImplementationBinding binding : phaseBindings) {
                    List<GroupInvocation> invocations = submitGroupedBinding(phase, binding, dataset.records(), groupCache, discoveredByKey, executor);
                    Set<String> changedFields = new LinkedHashSet<>();
                    for (GroupInvocation invocation : invocations) {
                        for (Response response : collectAndFanOut(phase, invocation)) {
                            responses.add(response);
                            completed++;
                            applyAmendments(recordsById, response);
                            changedFields.addAll(response.amendments().keySet());
                            progressListener.onResponse(phase, response, completed, total);
                        }
                    }
                    groupCache.invalidate(changedFields);
                }
            } else {
                List<GroupInvocation> invocations = new ArrayList<>();
                for (ImplementationBinding binding : phaseBindings) {
                    invocations.addAll(submitGroupedBinding(phase, binding, dataset.records(), groupCache, discoveredByKey, executor));
                }
                for (GroupInvocation invocation : invocations) {
                    for (Response response : collectAndFanOut(phase, invocation)) {
                        responses.add(response);
                        completed++;
                        progressListener.onResponse(phase, response, completed, total);
                    }
                }
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
        } catch (Exception e) {
            LOG.error("Execution failed in phase {} with {} records, {} direct bindings, {} built-in measures",
                    phase, dataset.records().size(), phaseBindings.size(), builtInMeasures.size(), e);
            throw new RuntimeException("Execution failed in phase " + phase, e);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Partitions {@code records} into groups for {@code binding} (via the shared
     * {@code groupCache} when {@code binding} is dedup-eligible and {@link #dedupEnabled}, or one
     * singleton group per record otherwise) and submits one {@link ExecutionAdapter#execute} task
     * per group to {@code executor}.
     *
     * @param phase the phase being executed, passed through to progress reporting
     * @param binding the binding to submit invocations for
     * @param records the phase's records
     * @param groupCache the phase-scoped shared partition cache
     * @param discoveredByKey discovered implementations keyed by
     *     {@code "<implementationClass>#<implementationMethod>"}
     * @param executor the thread pool to submit invocations to
     * @return one pending {@link GroupInvocation} per group, in group order
     */
    private List<GroupInvocation> submitGroupedBinding(
            Phase phase,
            ImplementationBinding binding,
            List<CanonicalRecord> records,
            PhaseGroupCache groupCache,
            Map<String, DiscoveredImplementation> discoveredByKey,
            ExecutorService executor) {
        List<RecordGroup> groups = groupsForBinding(binding, records, groupCache);
        String implementationKey = binding.implementationClass() + "#" + binding.implementationMethod();
        List<GroupInvocation> invocations = new ArrayList<>(groups.size());
        for (RecordGroup group : groups) {
            invocations.add(new GroupInvocation(binding, group, executor.submit(() -> {
                progressListener.onTaskStarted(phase);
                try {
                    return executionAdapter.execute(
                            group.representative(),
                            binding,
                            discoveredByKey.get(implementationKey));
                } finally {
                    progressListener.onTaskFinished(phase);
                }
            })));
        }
        return invocations;
    }

    /**
     * Resolves the distinct-value groups a binding should be invoked against: the shared
     * {@code groupCache}'s partition for {@code binding}'s declared fields when it is
     * dedup-eligible and {@link #dedupEnabled} is {@code true}, or one singleton group per record
     * (bypassing the cache entirely) otherwise — exactly reproducing today's one-invocation-per-record
     * behavior for ineligible bindings or when dedup is disabled.
     *
     * @param binding the binding to resolve groups for
     * @param records the phase's records
     * @param groupCache the phase-scoped shared partition cache
     * @return the groups to invoke {@code binding} against
     */
    private List<RecordGroup> groupsForBinding(ImplementationBinding binding, List<CanonicalRecord> records, PhaseGroupCache groupCache) {
        if (!dedupEnabled || !isDedupEligible(binding)) {
            return records.stream().map(record -> new RecordGroup(record, List.of(record.id()))).toList();
        }
        return groupCache.groupsFor(canonicalFields(binding));
    }

    /**
     * Determines whether a binding's declared inputs are precise enough to safely group records
     * by: {@code true} unless any of its parameters has role {@code LEGACY_RECORD} or
     * {@code LEGACY_PARAMETERS}, which read the whole record/parameter map rather than specific
     * declared Darwin Core terms, so the workbench cannot know what subset of fields the
     * implementation actually depends on.
     *
     * @param binding the binding to check
     * @return {@code true} if {@code binding} may be grouped by its declared
     *     {@code ACTED_UPON}/{@code CONSULTED} terms
     */
    private static boolean isDedupEligible(ImplementationBinding binding) {
        return binding.parameterBindings().stream()
                .map(bound -> bound.parameter().role())
                .noneMatch(role -> role == ParameterRole.LEGACY_RECORD || role == ParameterRole.LEGACY_PARAMETERS);
    }

    /**
     * Extracts the canonical (sorted, deduplicated) Darwin Core term names a binding declares as
     * {@code ACTED_UPON} or {@code CONSULTED} input, in exactly the form
     * {@link ReflectionExecutionAdapter} looks them up by
     * ({@link BoundMethodParameter#resolvedSource()}), so the resulting group partition reflects
     * precisely the values the binding's implementation would read at invocation time.
     *
     * @param binding the binding to extract fields from
     * @return the binding's canonical field set; empty if it declares no such terms, in which case
     *     every record groups together since the binding is invariant across all of them
     */
    private static List<String> canonicalFields(ImplementationBinding binding) {
        return binding.parameterBindings().stream()
                .filter(bound -> bound.parameter().role() == ParameterRole.ACTED_UPON || bound.parameter().role() == ParameterRole.CONSULTED)
                .map(BoundMethodParameter::resolvedSource)
                .filter(source -> source != null)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Collects one group's invocation result (converting an execution failure into an
     * {@link OutcomeStatus#ERROR} response, exactly as {@link #executePhase} did per-record before
     * distinct-value execution) and copies it to every member of the group.
     *
     * @param phase the phase the invocation belongs to, used for error logging
     * @param invocation the pending group invocation to collect
     * @return one response per {@link RecordGroup#memberRecordIds()} of {@code invocation}'s group,
     *     all identical except for {@link Response#recordId()}
     */
    private List<Response> collectAndFanOut(Phase phase, GroupInvocation invocation) {
        Response representative;
        try {
            representative = invocation.future().get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            LOG.error("Unhandled execution failure in phase {} for test {} using {}.{} on record {}: {}",
                    phase,
                    invocation.binding().testId(),
                    invocation.binding().implementationClass(),
                    invocation.binding().implementationMethod(),
                    invocation.group().representative().id(),
                    cause.getMessage(),
                    cause);
            representative = errorResponse(invocation.group().representative().id(), invocation.binding(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Execution interrupted in phase " + phase, e);
        }
        List<String> memberIds = invocation.group().memberRecordIds();
        List<Response> fanned = new ArrayList<>(memberIds.size());
        for (String memberId : memberIds) {
            fanned.add(memberId.equals(representative.recordId()) ? representative : withRecordId(representative, memberId));
        }
        return fanned;
    }

    /**
     * Copies a response, replacing only its {@link Response#recordId()}, used to fan a single
     * group invocation's result out to every other record sharing that group.
     *
     * @param response the response to copy
     * @param recordId the record ID the copy should carry
     * @return an identical response except for its record ID
     */
    private static Response withRecordId(Response response, String recordId) {
        return new Response(
                recordId,
                response.testId(),
                response.testType(),
                response.implementationClass(),
                response.implementationMethod(),
                response.phase(),
                response.parameters(),
                response.status(),
                response.responseStatus(),
                response.responseResult(),
                response.comment(),
                response.message(),
                response.amendments(),
                response.startedAt(),
                response.finishedAt());
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
     * Merges an AMENDMENT-phase response's amendments into the matching record's term map, so
     * that later bindings in the same (or a subsequent) phase observe the amended values. Does
     * nothing if the response carries no amendments or if no record with a matching ID is found.
     *
     * @param recordsById the phase's records, keyed by ID, whose matching entry's terms are
     *     updated in place
     * @param response the response whose {@link Response#amendments()} are to be applied
     */
    private static void applyAmendments(Map<String, CanonicalRecord> recordsById, Response response) {
        if (response.amendments().isEmpty()) {
            return;
        }
        CanonicalRecord record = recordsById.get(response.recordId());
        if (record == null) {
            return;
        }
        LOG.debug("Applying amendments for record {}: {}", response.recordId(), response.amendments());
        response.amendments().forEach(record.terms()::put);
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
     * A submitted group invocation awaiting completion, paired with the binding and group it was
     * submitted for so that failures can be attributed correctly and the result can be fanned out
     * to every member of the group once the future completes.
     *
     * @param binding the binding the invocation was submitted for
     * @param group the distinct-value group the invocation was submitted for (invoked against
     *     {@link RecordGroup#representative()}, applicable to every {@link RecordGroup#memberRecordIds()})
     * @param future the pending result of the invocation
     */
    private record GroupInvocation(ImplementationBinding binding, RecordGroup group, Future<Response> future) {
    }
}
