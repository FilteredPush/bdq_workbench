/** ExecutionProgressListener.java
 *
 * Callback interface for observing the progress of a test execution run, suitable for driving UI or console progress reporting.
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

import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

/**
 * Listener for execution progress updates, suitable for driving a UI progress bar or console
 * output while a {@link TestExecutionService#execute} run is in flight.
 *
 * <p>All methods have empty default implementations, so implementers need only override the
 * callbacks they care about. {@link ParallelPhaseExecutionService} drives these callbacks as it
 * works through each {@link Phase} of a run in turn: {@link #onPhaseStarted} once at the start of
 * a phase, {@link #onTaskStarted}/{@link #onTaskFinished} bracketing each record/test invocation
 * from the worker thread that runs it (not called for synthesized built-in measures, which are
 * computed synchronously rather than via the thread pool), {@link #onResponse} once for every
 * response produced (including synthesized built-in measure responses) as work streams back from
 * its thread pool, and {@link #onPhaseCompleted} once the phase's work is exhausted.
 *
 * <p>{@code onTaskStarted}/{@code onTaskFinished} are deliberately separate from
 * {@code onResponse} for tracking genuine concurrency: futures are collected by the main thread
 * strictly in submission order, so if an early-submitted task happens to run long, the main thread
 * can be blocked reporting no responses yet while many later-submitted tasks have already started
 * and finished out of order. Pairing the increment/decrement with actual task start/finish (from
 * the worker thread itself) keeps the count accurate regardless of collection order.
 */
public interface ExecutionProgressListener {

    /**
     * Invoked once when a phase begins, before any responses have been produced for it.
     *
     * @param phase the phase that is starting
     * @param total the total number of units of work (record/test invocations plus any built-in
     *     measures) expected to complete during this phase
     */
    default void onPhaseStarted(Phase phase, int total) {
    }

    /**
     * Invoked once per record/test invocation, from the worker thread that picks it up, right
     * before the implementation is actually invoked — i.e. while it is genuinely executing
     * concurrently with up to {@code threadCount} other invocations. Always paired with a matching
     * {@link #onTaskFinished} call from the same worker thread once the invocation completes.
     *
     * @param phase the phase the invocation belongs to
     */
    default void onTaskStarted(Phase phase) {
    }

    /**
     * Invoked once per record/test invocation, from the worker thread that ran it, immediately
     * after the implementation has finished (successfully or not) — the counterpart to
     * {@link #onTaskStarted}, used to track true concurrency independent of when the main thread
     * gets around to collecting that invocation's result via {@link #onResponse}.
     *
     * @param phase the phase the invocation belongs to
     */
    default void onTaskFinished(Phase phase) {
    }

    /**
     * Invoked once for each response produced during a phase, as it completes. Because
     * invocations within a phase run concurrently, responses may be reported in a different order
     * than the records/tests were submitted.
     *
     * @param phase the phase the response belongs to
     * @param response the response that was just produced
     * @param completed the number of units of work completed so far in this phase, including this
     *     response
     * @param total the total number of units of work expected to complete during this phase
     */
    default void onResponse(Phase phase, Response response, int completed, int total) {
    }

    /**
     * Invoked once when a phase has finished, after all of its responses have already been
     * reported via {@link #onResponse}.
     *
     * @param phase the phase that completed
     * @param completed the number of units of work completed in this phase
     * @param total the total number of units of work expected to complete during this phase
     */
    default void onPhaseCompleted(Phase phase, int completed, int total) {
    }
}
