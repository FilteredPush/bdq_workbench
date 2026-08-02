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

/** Executes policy bindings in pre/amendment/post phases with deterministic ordering. */
public class ParallelPhaseExecutionService implements TestExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(ParallelPhaseExecutionService.class);
    private final int threadCount;
    private final ExecutionAdapter executionAdapter;
    private final ExecutionProgressListener progressListener;

    public ParallelPhaseExecutionService(int threadCount, ExecutionAdapter executionAdapter) {
        this(threadCount, executionAdapter, new ExecutionProgressListener() {
        });
    }

    public ParallelPhaseExecutionService(
            int threadCount,
            ExecutionAdapter executionAdapter,
            ExecutionProgressListener progressListener) {
        this.threadCount = Math.max(1, threadCount);
        this.executionAdapter = executionAdapter;
        this.progressListener = progressListener;
    }

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
                            executor.submit(() -> executionAdapter.execute(
                                    record,
                                    binding,
                                    discoveredByKey.get(implementationKey)))));
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

    private static boolean hasTargetBindingForPhase(
            ImplementationBinding measureBinding,
            List<ImplementationBinding> directPhaseBindings) {
        return BuiltInMeasureSpec.from(measureBinding)
                .map(spec -> directPhaseBindings.stream()
                        .filter(binding -> !BuiltInMeasureSpec.isBuiltIn(binding))
                        .anyMatch(binding -> spec.targetTestId().equals(binding.testId())))
                .orElse(false);
    }

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

    private record PendingExecution(String recordId, ImplementationBinding binding, Future<Response> future) {
    }
}
