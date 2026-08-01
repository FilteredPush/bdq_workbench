package org.filteredpush.bdq_workbench.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.filteredpush.bdq_workbench.execution.ExecutionProgressListener;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

/** Collects execution progress and summary counters for GUI display. */
public class ExecutionProgressTracker implements ExecutionProgressListener {
    private Phase phase;
    private int total;
    private int completed;
    private final Map<String, Long> statusCounts = new LinkedHashMap<>();
    private final Map<String, Long> resultCounts = new LinkedHashMap<>();

    @Override
    public synchronized void onPhaseStarted(Phase phase, int total) {
        this.phase = phase;
        this.total = total;
        this.completed = 0;
        statusCounts.clear();
        resultCounts.clear();
    }

    @Override
    public synchronized void onResponse(Phase phase, Response response, int completed, int total) {
        this.phase = phase;
        this.total = total;
        this.completed = completed;
        increment(statusCounts, response.responseStatus());
        increment(resultCounts, response.responseResult());
    }

    public synchronized ExecutionProgressSnapshot snapshot() {
        int running = total == 0 ? 0 : Math.min(1, total - completed);
        int queued = Math.max(0, total - completed - running);
        return new ExecutionProgressSnapshot(
                phase,
                queued,
                running,
                completed,
                total,
                Map.copyOf(statusCounts),
                Map.copyOf(resultCounts));
    }

    private static void increment(Map<String, Long> counts, String key) {
        String normalized = key == null || key.isBlank() ? "<none>" : key;
        counts.merge(normalized, 1L, Long::sum);
    }
}
