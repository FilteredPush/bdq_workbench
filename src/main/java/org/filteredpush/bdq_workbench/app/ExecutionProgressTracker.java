/** ExecutionProgressTracker.java
 *
 * Thread-safe listener that accumulates execution progress and per-status/result counters,
 * exposing them as immutable {@link ExecutionProgressSnapshot} instances for GUI polling.
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

import java.util.LinkedHashMap;
import java.util.Map;
import org.filteredpush.bdq_workbench.execution.ExecutionProgressListener;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

/**
 * Collects execution progress and summary counters for GUI display.
 *
 * <p>Implements {@link ExecutionProgressListener} to receive callbacks from the execution
 * service (typically {@link org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionService})
 * as tests run, and exposes the accumulated state as a point-in-time
 * {@link ExecutionProgressSnapshot} via {@link #snapshot()} for the GUI to poll from a timer or
 * refresh thread. All methods are synchronized so callbacks (from execution worker threads) and
 * snapshot reads (from the GUI thread) are safely interleaved.
 */
public class ExecutionProgressTracker implements ExecutionProgressListener {
    private Phase phase;
    private int total;
    private int completed;
    private final Map<String, Long> statusCounts = new LinkedHashMap<>();
    private final Map<String, Long> resultCounts = new LinkedHashMap<>();

    /**
     * Resets counters for the start of a new phase.
     *
     * @param phase the phase beginning execution
     * @param total total number of tests scheduled for this phase
     */
    @Override
    public synchronized void onPhaseStarted(Phase phase, int total) {
        this.phase = phase;
        this.total = total;
        this.completed = 0;
        statusCounts.clear();
        resultCounts.clear();
    }

    /**
     * Records a completed response, updating progress counts and the running
     * status/result tallies.
     *
     * @param phase the phase the response belongs to
     * @param response the completed response
     * @param completed number of tests completed so far in this phase, inclusive of this one
     * @param total total number of tests in this phase
     */
    @Override
    public synchronized void onResponse(Phase phase, Response response, int completed, int total) {
        this.phase = phase;
        this.total = total;
        this.completed = completed;
        increment(statusCounts, response.responseStatus());
        increment(resultCounts, response.responseResult());
    }

    /**
     * Captures the current progress state as an immutable snapshot.
     *
     * <p>{@code running} is derived as {@code min(1, total - completed)} since progress is
     * reported one completed response at a time, and {@code queued} is whatever remains after
     * that.
     *
     * @return a snapshot of the current phase, progress counts, and status/result tallies
     */
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

    /**
     * Increments the tally for {@code key}, normalizing a null/blank key to {@code "<none>"}.
     *
     * @param counts the tally map to update
     * @param key the key to increment
     */
    private static void increment(Map<String, Long> counts, String key) {
        String normalized = key == null || key.isBlank() ? "<none>" : key;
        counts.merge(normalized, 1L, Long::sum);
    }
}
