package org.filteredpush.bdq_workbench.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;

/** Executes policy bindings in pre/amendment/post phases with deterministic ordering. */
public class ParallelPhaseExecutionService implements TestExecutionService {
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

        results.addAll(executePhase(Phase.PRE_AMENDMENT, immutableSource, bindings, discoveredByKey));
        results.addAll(executePhase(Phase.AMENDMENT, amendmentCopy, bindings, discoveredByKey));
        results.addAll(executePhase(Phase.POST_AMENDMENT, amendmentCopy, bindings, discoveredByKey));

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
                .filter(binding -> binding.phase() == phase)
                .filter(binding -> !BuiltInMeasureSpec.isBuiltIn(binding))
                .toList();
        List<ImplementationBinding> builtInMeasures = phase == Phase.AMENDMENT
                ? List.of()
                : bindings.stream()
                        .filter(BuiltInMeasureSpec::isBuiltIn)
                        .filter(binding -> hasTargetBindingForPhase(binding, phase, bindings))
                        .toList();
        if (phaseBindings.isEmpty() && builtInMeasures.isEmpty()) {
            return List.of();
        }
        int total = dataset.records().size() * phaseBindings.size() + builtInMeasures.size();
        progressListener.onPhaseStarted(phase, total);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (var record : dataset.records()) {
                for (var binding : phaseBindings) {
                    futures.add(executor.submit(() -> executionAdapter.execute(
                            record,
                            binding,
                            discoveredByKey.get(binding.implementationClass() + "#" + binding.implementationMethod()))));
                }
            }
            List<Response> responses = new ArrayList<>();
            int completed = 0;
            for (Future<Response> future : futures) {
                Response response = future.get();
                responses.add(response);
                completed++;
                if (phase == Phase.AMENDMENT) {
                    applyAmendments(dataset, response);
                }
                progressListener.onResponse(phase, response, completed, total);
            }
            for (ImplementationBinding measureBinding : builtInMeasures) {
                Response response = synthesizeBuiltInMeasure(phase, dataset, measureBinding, responses);
                responses.add(response);
                completed++;
                progressListener.onResponse(phase, response, completed, total);
            }
            progressListener.onPhaseCompleted(phase, completed, total);
            return responses;
        } catch (Exception e) {
            throw new RuntimeException("Execution failed in phase " + phase, e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean hasTargetBindingForPhase(
            ImplementationBinding measureBinding,
            Phase phase,
            List<ImplementationBinding> allBindings) {
        return BuiltInMeasureSpec.from(measureBinding)
                .map(spec -> allBindings.stream()
                        .filter(binding -> !BuiltInMeasureSpec.isBuiltIn(binding))
                        .anyMatch(binding -> binding.phase() == phase && spec.targetTestId().equals(binding.testId())))
                .orElse(false);
    }

    private static Response synthesizeBuiltInMeasure(
            Phase phase,
            RecordDataset dataset,
            ImplementationBinding measureBinding,
            List<Response> phaseResponses) {
        BuiltInMeasureSpec spec = BuiltInMeasureSpec.from(measureBinding)
                .orElseThrow(() -> new IllegalArgumentException("Not a built-in measure binding: " + measureBinding.testId()));
        long matchingCount = phaseResponses.stream()
                .filter(response -> spec.targetTestId().equals(response.testId()))
                .filter(response -> spec.responseResult().equals(response.responseResult()))
                .count();
        int totalRecords = dataset.records().size();
        double percentage = totalRecords == 0 ? 0.0d : (matchingCount * 100.0d) / totalRecords;
        java.time.Instant finishedAt = java.time.Instant.now();
        String message = String.format(
                "%d/%d records matched %s for %s (%.1f%%)",
                matchingCount,
                totalRecords,
                spec.responseResult(),
                spec.targetTestLabel(),
                percentage);
        return new Response(
                "MULTIRECORD",
                measureBinding.testId(),
                measureBinding.testType(),
                measureBinding.implementationClass(),
                measureBinding.implementationMethod(),
                phase,
                Map.of(),
                OutcomeStatus.PASSED,
                "RUN_HAS_RESULT",
                Long.toString(matchingCount),
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
        dataset.records().stream()
                .filter(record -> record.id().equals(response.recordId()))
                .findFirst()
                .ifPresent(record -> response.amendments().forEach(record.terms()::put));
    }
}
