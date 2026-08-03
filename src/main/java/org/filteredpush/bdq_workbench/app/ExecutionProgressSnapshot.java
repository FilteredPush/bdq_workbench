/** ExecutionProgressSnapshot.java
 *
 * Immutable point-in-time snapshot of execution progress, for polling by the GUI.
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

import java.util.Map;
import org.filteredpush.bdq_workbench.model.Phase;

/**
 * Immutable progress state for UI updates.
 *
 * <p>Produced by {@link ExecutionProgressTracker#snapshot()} from the counters it accumulates
 * via {@link org.filteredpush.bdq_workbench.execution.ExecutionProgressListener} callbacks, for
 * the GUI to poll and render (e.g. a progress bar and running status/result tallies) while a
 * phase executes.
 *
 * @param phase the phase currently executing (or last executed)
 * @param queued number of tests not yet started
 * @param running number of tests currently in flight (0 or 1, since progress is reported one
 *     completed response at a time)
 * @param completed number of tests completed so far in this phase
 * @param total total number of tests in this phase
 * @param statusCounts running tally of responses by {@code responseStatus}, keyed by status
 *     (blank/null statuses are recorded under {@code "<none>"})
 * @param resultCounts running tally of responses by {@code responseResult}, keyed by result
 *     (blank/null results are recorded under {@code "<none>"})
 */
public record ExecutionProgressSnapshot(
        Phase phase,
        int queued,
        int running,
        int completed,
        int total,
        Map<String, Long> statusCounts,
        Map<String, Long> resultCounts) {
}
