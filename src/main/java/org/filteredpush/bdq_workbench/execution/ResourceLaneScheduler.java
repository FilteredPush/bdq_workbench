/** ResourceLaneScheduler.java
 *
 * Non-blocking dispatcher that runs test invocations on a bounded worker pool while limiting how
 * many invocations of each resource lane run at once, adapting those limits to observed outcomes.
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches invocations to a fixed worker pool through per-resource lanes (bulkheads), adapting
 * each lane's concurrency to the outcomes it observes.
 *
 * <p><b>Lanes.</b> Submitted work waits in its lane's queue, not in the worker pool, so waiting
 * work never occupies a worker. Whenever a worker is free, the dispatcher starts the
 * earliest-submitted queued invocation whose lane has a free permit; other lanes' work proceeds
 * while one lane is saturated, paused, or unavailable. Completion releases the lane's permit and
 * the worker, and dispatches again. With the service's round-robin submission order (see
 * {@link FairDispatchOrder}) this keeps work spread across bindings as well as across lanes.
 *
 * <p><b>Adaptive limits.</b> When {@link ExecutionPolicy#adaptiveThrottlingEnabled()}, every
 * response is classified by {@link ResponseFailureClassifier}. A transient or ambiguous external
 * failure halves the lane's limit (minimum 1) and, for a lane not configured explicitly, lowers the
 * ceiling it may grow back to to the external default: an unclassified or hinted-local lane that
 * fails like an external service is from then on treated as one. Every
 * {@link ExecutionPolicy#successesBeforeIncrease()} successes raise the limit by one, up to the
 * ceiling. A lane not configured explicitly that has seen no external failure after
 * {@link ExecutionPolicy#localPromotionSuccesses()} successes has its ceiling raised to the local
 * limit, so a source-authority hint that named a local data layer is outgrown gradually.
 * Configuration and internal failures change nothing.
 *
 * <p><b>Circuit breaker.</b> {@link ExecutionPolicy#circuitFailureThreshold()} consecutive external
 * failures open the lane's circuit: its queued work stays queued, without holding a worker, until
 * a cooldown elapses on the {@link DelayScheduler}; then one probe runs (half-open). A successful
 * probe closes the circuit and the lane resumes at a limit of 1, growing again additively; a failed
 * probe reopens it with the cooldown doubled, up to {@link ExecutionPolicy#circuitMaxCooldown()}.
 * Once {@link ExecutionPolicy#circuitMaxOpenings()} consecutive openings have passed without a
 * successful probe, the next failure makes the lane unavailable for the rest of the run: its
 * queued and later work is completed, without invoking anything, with the response the
 * submitter's {@code unavailable} function builds from a diagnostic naming the lane and its last
 * failure. This bounds how long a run can wait on a dead service, and since only the failing lane
 * pauses, local work and unrelated lanes keep running and no combination of open circuits can
 * deadlock the run.
 *
 * <p><b>Retries.</b> A group invocation whose response is a likely transient (or ambiguous)
 * external failure is retried as allowed by {@link RetryPolicy}: after a backoff on the
 * {@link DelayScheduler} (never on a worker thread) it rejoins its lane's queue with its original
 * submission order, so it is subject to the lane's current limit and circuit state like any other
 * work, and unrelated lanes run meanwhile. Its result future completes only with the final
 * attempt's response, so callers (and amendment write-back) only ever see settled results. The
 * final response's message is annotated with the attempt count; its comment and status are the
 * implementation's own, so exhausted retries still report the latest external-prerequisite
 * diagnostic rather than a generic error.
 *
 * <p>Lanes are scoped to the scheduler, which lives for one execution run, so what is learned
 * about a resource in PRE_AMENDMENT carries over to later phases. All state is guarded by one
 * lock; the invocation itself, completion of result futures, and listener notification happen
 * outside it. {@link #close()} cancels queued work and pending cooldowns and interrupts in-flight
 * work.
 */
final class ResourceLaneScheduler implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(ResourceLaneScheduler.class);
	private static final int MAX_DIAGNOSTIC_LENGTH = 300;

	private final Object lock = new Object();
	private final int workerCount;
	private final int externalLimit;
	private final int localLimit;
	private final ExecutionPolicy policy;
	private final ExecutorService workers;
	private final DelayScheduler delays;
	private final LaneEventSink events;
	private final ExecutionStatisticsCollector statistics;
	private final RetryPolicy retries;
	private final Set<Task> retryPending = ConcurrentHashMap.newKeySet();
	private final Map<ExecutionResourceKey, Lane> lanes = new LinkedHashMap<>();
	private long sequence;
	private int inFlight;
	private boolean closed;

	/**
	 * Receives resource lane events.
	 */
	@FunctionalInterface
	interface LaneEventSink {
		/**
		 * Accepts one event.
		 *
		 * @param phase the phase the event occurred in
		 * @param event the event
		 */
		void accept(Phase phase, ResourceLaneEvent event);
	}

	/**
	 * Circuit breaker states of a lane.
	 */
	enum CircuitState {
		/** Normal operation. */
		CLOSED,
		/** Paused until a cooldown elapses. */
		OPEN,
		/** One probe invocation may run. */
		HALF_OPEN,
		/** Given up on for the rest of the run. */
		UNAVAILABLE
	}

	/**
	 * Creates a scheduler with default policy, its own timer, and no event sink.
	 *
	 * @param workerCount the number of worker threads, at least 1
	 */
	ResourceLaneScheduler(int workerCount) {
		this(workerCount, ExecutionPolicy.defaults(), DelayScheduler.newDefault(), (phase, event) -> {
		}, new ExecutionStatisticsCollector(), new RetryPolicy(ExecutionPolicy.defaults()));
	}

	/**
	 * Creates a scheduler with its own worker pool.
	 *
	 * @param workerCount the number of worker threads, at least 1
	 * @param policy the execution policy governing adaptation and circuit breaking
	 * @param delays the timer used for cooldowns; closed when this scheduler is closed
	 * @param events receives lane events
	 * @param statistics accumulates run statistics
	 */
	ResourceLaneScheduler(
			int workerCount,
			ExecutionPolicy policy,
			DelayScheduler delays,
			LaneEventSink events,
			ExecutionStatisticsCollector statistics) {
		this(workerCount, policy, delays, events, statistics, new RetryPolicy(policy));
	}

	/**
	 * Creates a scheduler with its own worker pool and an explicit retry policy.
	 *
	 * @param workerCount the number of worker threads, at least 1
	 * @param policy the execution policy governing adaptation and circuit breaking
	 * @param delays the timer used for cooldowns and retry backoff; closed when this scheduler is
	 *     closed
	 * @param events receives lane events
	 * @param statistics accumulates run statistics
	 * @param retries decides which failures are retried and when
	 */
	ResourceLaneScheduler(
			int workerCount,
			ExecutionPolicy policy,
			DelayScheduler delays,
			LaneEventSink events,
			ExecutionStatisticsCollector statistics,
			RetryPolicy retries) {
		this.retries = retries;
		this.workerCount = Math.max(1, workerCount);
		this.policy = policy;
		this.externalLimit = Math.min(this.workerCount, policy.externalConcurrency());
		this.localLimit = policy.localConcurrency() > 0
				? Math.min(this.workerCount, policy.localConcurrency())
				: this.workerCount;
		this.delays = delays;
		this.events = events;
		this.statistics = statistics;
		this.workers = Executors.newFixedThreadPool(this.workerCount, namedThreads("bdq-worker"));
	}

	/**
	 * Queues one invocation in its resource lane and dispatches whatever can run. If the lane is
	 * unavailable the result is completed exceptionally instead.
	 *
	 * @param phase the phase the invocation belongs to
	 * @param assignment the binding's resource lane assignment
	 * @param binding the binding being invoked
	 * @param task the invocation
	 * @param result completed with the invocation's response, or exceptionally with what it threw,
	 *     or cancelled if the scheduler is closed first
	 */
	void submit(
			Phase phase,
			ResourceAssignment assignment,
			ImplementationBinding binding,
			Callable<Response> task,
			CompletableFuture<Response> result) {
		submit(phase, assignment, binding, task, null, result);
	}

	/**
	 * Queues one invocation in its resource lane and dispatches whatever can run.
	 *
	 * @param phase the phase the invocation belongs to
	 * @param assignment the binding's resource lane assignment
	 * @param binding the binding being invoked
	 * @param task the invocation
	 * @param unavailable builds the response reported, without invoking {@code task}, if the lane
	 *     becomes unavailable, from a diagnostic message; {@code null} to complete the result
	 *     exceptionally instead
	 * @param result completed with the invocation's response, or exceptionally with what it threw,
	 *     or cancelled if the scheduler is closed first
	 */
	void submit(
			Phase phase,
			ResourceAssignment assignment,
			ImplementationBinding binding,
			Callable<Response> task,
			Function<String, Response> unavailable,
			CompletableFuture<Response> result) {
		List<Runnable> after = new ArrayList<>();
		synchronized (lock) {
			if (closed) {
				after.add(() -> result.cancel(false));
			} else {
				Lane lane = laneFor(assignment);
				lane.lastPhase = phase;
				lane.queue.add(new Task(sequence++, phase, lane, binding, task, unavailable, result));
				drain(after);
			}
		}
		runAll(after);
	}

	/**
	 * Returns (creating on first use) the lane for an assignment. Must hold {@link #lock}.
	 *
	 * @param assignment the assignment
	 * @return the lane
	 */
	private Lane laneFor(ResourceAssignment assignment) {
		Lane lane = lanes.get(assignment.key());
		if (lane == null) {
			lane = new Lane(assignment);
			lanes.put(assignment.key(), lane);
			LOG.info("Resource lane {} [{}] limit {}: {}", assignment.key(), assignment.resourceClass(),
					assignment.maxConcurrency(), assignment.reason());
		} else {
			lane.absorb(assignment);
		}
		return lane;
	}

	/**
	 * Skips the work of unavailable lanes, then starts queued invocations while workers and lane
	 * permits are available. Must hold {@link #lock}.
	 *
	 * @param after collects actions to run once the lock is released
	 */
	private void drain(List<Runnable> after) {
		if (closed) {
			return;
		}
		for (Lane lane : lanes.values()) {
			if (lane.state == CircuitState.UNAVAILABLE) {
				while (!lane.queue.isEmpty()) {
					skip(lane.queue.poll(), after);
				}
			}
		}
		while (inFlight < workerCount) {
			Lane next = null;
			for (Lane lane : lanes.values()) {
				if (!lane.queue.isEmpty() && lane.hasPermit()
						&& (next == null || lane.queue.peek().sequence < next.queue.peek().sequence)) {
					next = lane;
				}
			}
			if (next == null) {
				return;
			}
			start(next.queue.poll());
		}
	}

	/**
	 * Takes a permit and hands the invocation to a worker. Must hold {@link #lock}.
	 *
	 * @param task the invocation to start
	 */
	private void start(Task task) {
		Lane lane = task.lane;
		inFlight++;
		lane.inFlight++;
		lane.maxObservedConcurrency = Math.max(lane.maxObservedConcurrency, lane.inFlight);
		if (lane.state == CircuitState.HALF_OPEN) {
			task.probe = true;
			lane.probeInFlight = true;
		}
		statistics.started(lane.key, task.binding.testId());
		workers.execute(() -> run(task));
	}

	/**
	 * Completes a task of an unavailable lane without invoking it. Must hold {@link #lock}.
	 *
	 * @param task the skipped task
	 * @param after collects actions to run once the lock is released
	 */
	private void skip(Task task, List<Runnable> after) {
		statistics.skipped(task.lane.key, task.binding.testId());
		String detail = task.lane.unavailableDetail();
		after.add(() -> {
			if (task.unavailable == null) {
				task.result.completeExceptionally(new IllegalStateException(detail));
			} else {
				task.result.complete(task.unavailable.apply(detail));
			}
		});
	}

	/**
	 * Runs one invocation on a worker thread and reports its outcome.
	 *
	 * @param task the invocation
	 */
	private void run(Task task) {
		Response response = null;
		Throwable failure = null;
		try {
			response = task.callable.call();
		} catch (Throwable t) {
			failure = t;
		}
		complete(task, response, failure);
	}

	/**
	 * Releases the invocation's permits, adapts its lane to the outcome, completes its result, and
	 * dispatches more work.
	 *
	 * @param task the finished invocation
	 * @param response its response, or {@code null} if it threw
	 * @param failure what it threw, or {@code null}
	 */
	private void complete(Task task, Response response, Throwable failure) {
		List<Runnable> after = new ArrayList<>();
		Response settled = response;
		boolean retrying = false;
		synchronized (lock) {
			inFlight--;
			task.lane.inFlight--;
			statistics.finished(task.lane.key, task.binding.testId());
			if (!closed) {
				FailureCategory category = observe(task, response, failure, after);
				if (failure == null) {
					retrying = scheduleRetryIfAllowed(task, category, after);
					if (!retrying && task.attempt > 1) {
						settled = settleRetried(task, response, category, after);
					}
				}
				drain(after);
			}
		}
		if (!retrying) {
			if (failure != null) {
				task.result.completeExceptionally(failure);
			} else {
				task.result.complete(settled);
			}
		}
		runAll(after);
	}

	/**
	 * Schedules a retry of a failed invocation if its failure category and attempt count allow
	 * one. Must hold {@link #lock}.
	 *
	 * @param task the failed invocation
	 * @param category its failure category
	 * @param after collects actions to run once the lock is released
	 * @return whether a retry was scheduled (in which case the result must not be completed yet)
	 */
	private boolean scheduleRetryIfAllowed(Task task, FailureCategory category, List<Runnable> after) {
		int retriesUsed = task.attempt - 1;
		if (retriesUsed >= retries.maxRetries(category)) {
			return false;
		}
		Duration delay = retries.delayBeforeRetry(retriesUsed + 1);
		task.attempt++;
		task.failedAttempts++;
		statistics.retryScheduled(task.lane.key, task.binding.testId());
		retryPending.add(task);
		task.retryHandle = delays.schedule(delay, () -> requeue(task));
		emit(task.phase, task.lane, ResourceLaneEvent.Type.RETRY_SCHEDULED, task.binding.testId(), task.attempt,
				category + " failure; retrying in " + delay.toMillis() + " ms: " + task.lane.lastDiagnostic, after);
		return true;
	}

	/**
	 * Reports the final outcome of an invocation that was retried and annotates its response's
	 * message with the attempt count. Must hold {@link #lock}.
	 *
	 * @param task the invocation
	 * @param response its final response
	 * @param category the final response's failure category
	 * @param after collects actions to run once the lock is released
	 * @return the annotated response
	 */
	private Response settleRetried(Task task, Response response, FailureCategory category, List<Runnable> after) {
		String note;
		if (category.isExternalHealthFailure()) {
			statistics.retryExhausted(task.lane.key, task.binding.testId());
			emit(task.phase, task.lane, ResourceLaneEvent.Type.RETRY_EXHAUSTED, task.binding.testId(), task.attempt,
					"still failing after " + task.attempt + " attempts: " + task.lane.lastDiagnostic, after);
			note = "[workbench: external prerequisite still not met after " + task.attempt + " attempts]";
		} else {
			if (category == FailureCategory.COMPLETED) {
				statistics.retryRecovered(task.lane.key, task.binding.testId());
				emit(task.phase, task.lane, ResourceLaneEvent.Type.RETRY_SUCCEEDED, task.binding.testId(), task.attempt,
						"succeeded after " + task.failedAttempts + " external failure(s)", after);
			}
			note = "[workbench: attempt " + task.attempt + " after " + task.failedAttempts
					+ " external failure(s)]";
		}
		return withMessageNote(response, note);
	}

	/**
	 * Returns a retried invocation to its lane's queue once its backoff has elapsed.
	 *
	 * @param task the invocation to retry
	 */
	private void requeue(Task task) {
		List<Runnable> after = new ArrayList<>();
		synchronized (lock) {
			if (closed || !retryPending.remove(task)) {
				return;
			}
			task.retryHandle = null;
			task.lane.queue.add(task);
			drain(after);
		}
		runAll(after);
	}

	/**
	 * Copies a response with a note appended to its message; the comment, status, and result are
	 * unchanged.
	 *
	 * @param response the response
	 * @param note the note
	 * @return the annotated copy
	 */
	static Response withMessageNote(Response response, String note) {
		String message = response.message() == null || response.message().isBlank()
				? note
				: response.message() + " " + note;
		return new Response(
				response.recordId(),
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
				message,
				response.amendments(),
				response.startedAt(),
				response.finishedAt(),
				response.subjectRef(),
				response.derived(),
				response.contributingSubjectRefs());
	}

	/**
	 * Classifies an outcome and adapts the task's lane to it. Must hold {@link #lock}.
	 *
	 * @param task the finished invocation
	 * @param response its response, or {@code null}
	 * @param failure what it threw, or {@code null}
	 * @param after collects actions to run once the lock is released
	 * @return the outcome's failure category
	 */
	private FailureCategory observe(Task task, Response response, Throwable failure, List<Runnable> after) {
		Lane lane = task.lane;
		boolean probe = task.probe;
		if (probe) {
			task.probe = false;
			lane.probeInFlight = false;
		}
		FailureCategory category = failure != null ? FailureCategory.INTERNAL : ResponseFailureClassifier.classify(response);
		if (category.isExternalHealthFailure()) {
			statistics.externalFailure(lane.key, task.binding.testId());
			lane.lastDiagnostic = diagnostic(response);
		}
		if (!policy.adaptiveThrottlingEnabled()) {
			return category;
		}
		if (category == FailureCategory.COMPLETED) {
			onSuccess(lane, task, probe, after);
		} else if (category.isExternalHealthFailure()) {
			onExternalFailure(lane, task, category, probe, after);
		}
		return category;
	}

	/**
	 * Adapts a lane to a successful invocation. Must hold {@link #lock}.
	 *
	 * @param lane the lane
	 * @param task the invocation
	 * @param probe whether the invocation was a half-open probe
	 * @param after collects actions to run once the lock is released
	 */
	private void onSuccess(Lane lane, Task task, boolean probe, List<Runnable> after) {
		lane.consecutiveFailures = 0;
		if (lane.state == CircuitState.HALF_OPEN && probe) {
			lane.state = CircuitState.CLOSED;
			lane.limit = 1;
			lane.successes = 0;
			lane.openingsSinceClose = 0;
			lane.cooldown = null;
			emit(task.phase, lane, ResourceLaneEvent.Type.CIRCUIT_CLOSED, task.binding.testId(), 0,
					"probe succeeded; resuming at concurrency 1", after);
			return;
		}
		if (lane.state != CircuitState.CLOSED) {
			return;
		}
		lane.successes++;
		lane.totalSuccesses++;
		if (!lane.pinned && !lane.externalFailureSeen && lane.resourceClass != ResourceClass.LOCAL
				&& policy.localPromotionSuccesses() > 0 && lane.totalSuccesses >= policy.localPromotionSuccesses()
				&& lane.ceiling < localLimit) {
			lane.ceiling = localLimit;
			emit(task.phase, lane, ResourceLaneEvent.Type.CAPACITY_RESTORED, null, 0,
					"no external failure in " + lane.totalSuccesses + " successful calls; capacity may grow to " + localLimit,
					after);
		}
		if (lane.limit >= lane.ceiling) {
			lane.successes = 0;
		} else if (lane.successes >= policy.successesBeforeIncrease()) {
			lane.successes = 0;
			lane.limit++;
			emit(task.phase, lane, ResourceLaneEvent.Type.CAPACITY_RESTORED, null, 0,
					"limit raised after " + policy.successesBeforeIncrease() + " successes", after);
		}
	}

	/**
	 * Adapts a lane to a transient or ambiguous external failure. Must hold {@link #lock}.
	 *
	 * @param lane the lane
	 * @param task the invocation
	 * @param category the failure's category
	 * @param probe whether the invocation was a half-open probe
	 * @param after collects actions to run once the lock is released
	 */
	private void onExternalFailure(Lane lane, Task task, FailureCategory category, boolean probe, List<Runnable> after) {
		lane.successes = 0;
		lane.externalFailureSeen = true;
		lane.consecutiveFailures++;
		if (!lane.pinned && lane.ceiling > externalLimit) {
			lane.ceiling = externalLimit;
		}
		int reduced = Math.max(1, Math.min(lane.limit / 2, lane.ceiling));
		if (reduced < lane.limit) {
			lane.limit = reduced;
			emit(task.phase, lane, ResourceLaneEvent.Type.THROTTLED, task.binding.testId(), 0,
					category + " failure: " + lane.lastDiagnostic, after);
		}
		if (lane.state == CircuitState.HALF_OPEN && probe) {
			open(lane, task.phase, "half-open probe failed", after);
		} else if (lane.state == CircuitState.CLOSED && policy.circuitFailureThreshold() > 0
				&& lane.consecutiveFailures >= policy.circuitFailureThreshold()) {
			open(lane, task.phase, lane.consecutiveFailures + " consecutive external failures", after);
		}
	}

	/**
	 * Opens a lane's circuit, or makes the lane unavailable once it has opened too often without a
	 * successful probe. Must hold {@link #lock}.
	 *
	 * @param lane the lane
	 * @param phase the current phase
	 * @param reason why the circuit is opening
	 * @param after collects actions to run once the lock is released
	 */
	private void open(Lane lane, Phase phase, String reason, List<Runnable> after) {
		if (lane.openingsSinceClose >= policy.circuitMaxOpenings()) {
			lane.state = CircuitState.UNAVAILABLE;
			emit(phase, lane, ResourceLaneEvent.Type.LANE_UNAVAILABLE, null, 0,
					reason + " after " + lane.openingsSinceClose + " circuit openings; remaining work will not be invoked",
					after);
			return;
		}
		lane.openingsSinceClose++;
		lane.cooldown = lane.cooldown == null
				? policy.circuitInitialCooldown()
				: min(lane.cooldown.multipliedBy(2), policy.circuitMaxCooldown());
		lane.state = CircuitState.OPEN;
		statistics.circuitOpened(lane.key);
		lane.cooldownHandle = delays.schedule(lane.cooldown, () -> halfOpen(lane));
		emit(phase, lane, ResourceLaneEvent.Type.CIRCUIT_OPENED, null, 0,
				reason + "; pausing for " + lane.cooldown.toMillis() + " ms", after);
	}

	/**
	 * Moves a lane from open to half-open when its cooldown elapses, letting one probe run.
	 *
	 * @param lane the lane
	 */
	private void halfOpen(Lane lane) {
		List<Runnable> after = new ArrayList<>();
		synchronized (lock) {
			if (closed || lane.state != CircuitState.OPEN) {
				return;
			}
			lane.state = CircuitState.HALF_OPEN;
			lane.cooldownHandle = null;
			emit(lane.lastPhase, lane, ResourceLaneEvent.Type.CIRCUIT_HALF_OPEN, null, 0,
					"cooldown elapsed; probing with one invocation", after);
			drain(after);
		}
		runAll(after);
	}

	/**
	 * Logs an event and queues its delivery to the sink. Must hold {@link #lock}.
	 *
	 * @param phase the phase
	 * @param lane the lane
	 * @param type the event type
	 * @param testId the test involved, or {@code null}
	 * @param attempt the attempt number for retry events, else 0
	 * @param detail a short explanation
	 * @param after collects actions to run once the lock is released
	 */
	private void emit(Phase phase, Lane lane, ResourceLaneEvent.Type type, String testId, int attempt, String detail,
			List<Runnable> after) {
		ResourceLaneEvent event = new ResourceLaneEvent(type, lane.key, testId, lane.limit, attempt, detail);
		switch (type) {
			case CIRCUIT_OPENED, LANE_UNAVAILABLE, RETRY_EXHAUSTED -> LOG.warn("Resource lane {} phase={}", event, phase);
			default -> LOG.info("Resource lane {} phase={}", event, phase);
		}
		after.add(() -> events.accept(phase, event));
	}

	/**
	 * Runs deferred actions, isolating failures of each.
	 *
	 * @param actions the actions
	 */
	private static void runAll(List<Runnable> actions) {
		for (Runnable action : actions) {
			try {
				action.run();
			} catch (RuntimeException e) {
				LOG.warn("Resource lane callback failed: {}", e.getMessage(), e);
			}
		}
	}

	/**
	 * Returns the highest number of concurrent invocations observed in each lane so far.
	 *
	 * @return observed maximum concurrency keyed by lane
	 */
	Map<ExecutionResourceKey, Integer> maxObservedConcurrency() {
		synchronized (lock) {
			Map<ExecutionResourceKey, Integer> observed = new LinkedHashMap<>();
			lanes.forEach((key, lane) -> observed.put(key, lane.maxObservedConcurrency));
			return observed;
		}
	}

	/**
	 * Returns a lane's current concurrency limit.
	 *
	 * @param key the lane
	 * @return the limit, or 0 if the lane has not been used
	 */
	int currentLimit(ExecutionResourceKey key) {
		synchronized (lock) {
			Lane lane = lanes.get(key);
			return lane == null ? 0 : lane.limit;
		}
	}

	/**
	 * Returns a lane's circuit state.
	 *
	 * @param key the lane
	 * @return the state, or {@link CircuitState#CLOSED} if the lane has not been used
	 */
	CircuitState circuitState(ExecutionResourceKey key) {
		synchronized (lock) {
			Lane lane = lanes.get(key);
			return lane == null ? CircuitState.CLOSED : lane.state;
		}
	}

	/**
	 * Cancels queued work, pending retries and cooldowns, interrupts in-flight work, and shuts the
	 * worker pool and timer down.
	 */
	@Override
	public void close() {
		List<Task> dropped = new ArrayList<>();
		synchronized (lock) {
			if (closed) {
				return;
			}
			closed = true;
			for (Lane lane : lanes.values()) {
				dropped.addAll(lane.queue);
				lane.queue.clear();
				if (lane.cooldownHandle != null) {
					lane.cooldownHandle.cancel();
					lane.cooldownHandle = null;
				}
			}
			for (Task task : retryPending) {
				if (task.retryHandle != null) {
					task.retryHandle.cancel();
				}
				dropped.add(task);
			}
			retryPending.clear();
		}
		dropped.forEach(task -> task.result.cancel(false));
		workers.shutdownNow();
		delays.close();
	}

	/**
	 * Returns the shorter of two durations.
	 *
	 * @param first one duration
	 * @param second another
	 * @return the shorter
	 */
	private static Duration min(Duration first, Duration second) {
		return first.compareTo(second) <= 0 ? first : second;
	}

	/**
	 * Extracts a short diagnostic from a failed response, without record data.
	 *
	 * @param response the response
	 * @return its comment (or message), trimmed and truncated; never {@code null}
	 */
	static String diagnostic(Response response) {
		if (response == null) {
			return "no response";
		}
		String text = response.comment() == null || response.comment().isBlank() ? response.message() : response.comment();
		text = text == null ? "" : text.strip();
		if (text.isEmpty()) {
			text = String.valueOf(response.responseStatus());
		}
		return text.length() > MAX_DIAGNOSTIC_LENGTH ? text.substring(0, MAX_DIAGNOSTIC_LENGTH) + "..." : text;
	}

	/**
	 * Creates a daemon thread factory with a name prefix.
	 *
	 * @param prefix the thread name prefix
	 * @return the factory
	 */
	static ThreadFactory namedThreads(String prefix) {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	/**
	 * One resource lane: its queue, limits, circuit state and counters. Guarded by the scheduler
	 * lock.
	 */
	private static final class Lane {
		private final ExecutionResourceKey key;
		private final PriorityQueue<Task> queue = new PriorityQueue<>(Comparator.comparingLong(task -> task.sequence));
		private ResourceClass resourceClass;
		private int assignedMax;
		private int limit;
		private int ceiling;
		private boolean pinned;
		private int inFlight;
		private int maxObservedConcurrency;
		private CircuitState state = CircuitState.CLOSED;
		private boolean probeInFlight;
		private int successes;
		private long totalSuccesses;
		private int consecutiveFailures;
		private boolean externalFailureSeen;
		private int openingsSinceClose;
		private Duration cooldown;
		private DelayScheduler.Cancellable cooldownHandle;
		private String lastDiagnostic = "";
		private Phase lastPhase = Phase.PRE_AMENDMENT;

		/**
		 * Creates a lane from its first assignment.
		 *
		 * @param assignment the assignment
		 */
		private Lane(ResourceAssignment assignment) {
			this.key = assignment.key();
			this.resourceClass = assignment.resourceClass();
			this.assignedMax = assignment.maxConcurrency();
			this.limit = assignment.maxConcurrency();
			this.ceiling = assignment.maxConcurrency();
			this.pinned = assignment.pinned();
		}

		/**
		 * Merges another binding's assignment to this lane: the lane keeps the most conservative
		 * limit any of its bindings asked for, unless one of them was configured explicitly, in
		 * which case the configured limit stands; an external assignment makes the lane external.
		 * Re-submitting an assignment no more conservative than those already seen changes
		 * nothing, so limits learned at run time are kept.
		 *
		 * @param assignment the other assignment
		 */
		private void absorb(ResourceAssignment assignment) {
			if (assignment.resourceClass() == ResourceClass.EXTERNAL) {
				resourceClass = ResourceClass.EXTERNAL;
			}
			if (assignment.pinned() && !pinned) {
				assignedMax = assignment.maxConcurrency();
				ceiling = assignedMax;
				limit = Math.min(limit, ceiling);
				pinned = true;
			} else if (assignment.pinned() == pinned && assignment.maxConcurrency() < assignedMax) {
				assignedMax = assignment.maxConcurrency();
				ceiling = Math.min(ceiling, assignedMax);
				limit = Math.min(limit, ceiling);
			}
		}

		/**
		 * Returns whether another invocation may start in this lane.
		 *
		 * @return whether the lane has a free permit
		 */
		private boolean hasPermit() {
			return switch (state) {
				case CLOSED -> inFlight < limit;
				case HALF_OPEN -> inFlight == 0 && !probeInFlight;
				default -> false;
			};
		}

		/**
		 * Describes why the lane's work is not being invoked.
		 *
		 * @return the diagnostic
		 */
		private String unavailableDetail() {
			return "Not invoked: resource " + key + " is unavailable after " + openingsSinceClose
					+ " circuit openings without a successful probe"
					+ (lastDiagnostic.isBlank() ? "" : "; last diagnostic: " + lastDiagnostic);
		}

		/**
		 * Describes the lane.
		 *
		 * @return the lane key
		 */
		@Override
		public String toString() {
			return key.id();
		}
	}

	/**
	 * One queued or running invocation.
	 */
	private static final class Task {
		private final long sequence;
		private final Phase phase;
		private final Lane lane;
		private final ImplementationBinding binding;
		private final Callable<Response> callable;
		private final Function<String, Response> unavailable;
		private final CompletableFuture<Response> result;
		private boolean probe;
		private int attempt = 1;
		private int failedAttempts;
		private DelayScheduler.Cancellable retryHandle;

		/**
		 * Creates a task.
		 *
		 * @param sequence submission order, used to dispatch fairly
		 * @param phase the phase the invocation belongs to
		 * @param lane the lane it runs in
		 * @param binding the binding being invoked
		 * @param callable the invocation
		 * @param unavailable builds the response for an unavailable lane, or {@code null}
		 * @param result the future to complete
		 */
		private Task(long sequence, Phase phase, Lane lane, ImplementationBinding binding, Callable<Response> callable,
				Function<String, Response> unavailable, CompletableFuture<Response> result) {
			this.sequence = sequence;
			this.phase = phase;
			this.lane = lane;
			this.binding = binding;
			this.callable = callable;
			this.unavailable = unavailable;
			this.result = result;
		}
	}
}
