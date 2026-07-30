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
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;

/** Executes policy bindings in pre/amendment/post phases with deterministic ordering. */
public class ParallelPhaseExecutionService implements TestExecutionService {
    private final int threadCount;
    private final ExecutionAdapter executionAdapter;

    public ParallelPhaseExecutionService(int threadCount, ExecutionAdapter executionAdapter) {
        this.threadCount = Math.max(1, threadCount);
        this.executionAdapter = executionAdapter;
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
        List<ImplementationBinding> phaseBindings = bindings.stream().filter(b -> b.phase() == phase).toList();
        if (phaseBindings.isEmpty()) {
            return List.of();
        }
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
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } catch (Exception e) {
            throw new RuntimeException("Execution failed in phase " + phase, e);
        } finally {
            executor.shutdownNow();
        }
    }
}
