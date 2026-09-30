/** ResourceLaneScheduler.java
 *
 * Non-blocking dispatcher that runs test invocations on a bounded worker pool while limiting how
 * many invocations of each resource lane run at once.
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
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches invocations to a fixed worker pool through per-resource lanes (bulkheads).
 *
 * <p>Submitted work waits in its lane's queue, not in the worker pool, so waiting work never
 * occupies a worker. Whenever a worker is free, the dispatcher starts the earliest-submitted
 * queued invocation whose lane has a free permit; other lanes' work proceeds while one lane is
 * saturated. Completion releases the lane's permit and the worker, and dispatches again. With
 * the service's round-robin submission order (see {@link FairDispatchOrder}) this keeps work
 * spread across bindings as well as across lanes.
 *
 * <p>Lanes are scoped to the scheduler, which lives for one execution run, so what is learned
 * about a resource in PRE_AMENDMENT carries over to later phases. All state is guarded by one
 * lock; worker threads only run the invocation itself outside it. {@link #close()} cancels queued
 * work and interrupts in-flight work.
 */
final class ResourceLaneScheduler implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(ResourceLaneScheduler.class);

	private final Object lock = new Object();
	private final int workerCount;
	private final ExecutorService workers;
	private final Map<ExecutionResourceKey, Lane> lanes = new LinkedHashMap<>();
	private long sequence;
	private int inFlight;
	private boolean closed;

	/**
	 * Creates a scheduler with its own worker pool.
	 *
	 * @param workerCount the number of worker threads, at least 1
	 */
	ResourceLaneScheduler(int workerCount) {
		this.workerCount = Math.max(1, workerCount);
		this.workers = Executors.newFixedThreadPool(this.workerCount, namedThreads("bdq-worker"));
	}

	/**
	 * Queues one invocation in its resource lane and dispatches whatever can run.
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
		synchronized (lock) {
			if (closed) {
				result.cancel(false);
				return;
			}
			Lane lane = laneFor(assignment);
			lane.queue.add(new Task(sequence++, phase, lane, binding, task, result));
			drain();
		}
	}

	/**
	 * Returns (creating on first use) the lane for an assignment. A lane shared by several
	 * bindings keeps the most conservative limit any of them asked for, unless one of them was
	 * configured explicitly, in which case the configured limit stands.
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
	 * Starts queued invocations while workers and lane permits are available. Must hold
	 * {@link #lock}.
	 */
	private void drain() {
		while (!closed && inFlight < workerCount) {
			Lane next = null;
			for (Lane lane : lanes.values()) {
				if (!lane.queue.isEmpty() && lane.hasPermit()
						&& (next == null || lane.queue.peek().sequence() < next.queue.peek().sequence())) {
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
		inFlight++;
		task.lane().inFlight++;
		task.lane().maxObservedConcurrency = Math.max(task.lane().maxObservedConcurrency, task.lane().inFlight);
		workers.execute(() -> run(task));
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
			response = task.callable().call();
		} catch (Throwable t) {
			failure = t;
		}
		complete(task, response, failure);
	}

	/**
	 * Releases the invocation's permits, completes its result, and dispatches more work.
	 *
	 * @param task the finished invocation
	 * @param response its response, or {@code null} if it threw
	 * @param failure what it threw, or {@code null}
	 */
	private void complete(Task task, Response response, Throwable failure) {
		synchronized (lock) {
			inFlight--;
			task.lane().inFlight--;
			drain();
		}
		if (failure != null) {
			task.result().completeExceptionally(failure);
		} else {
			task.result().complete(response);
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
	 * Cancels queued work, interrupts in-flight work, and shuts the worker pool down.
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
			}
		}
		dropped.forEach(task -> task.result().cancel(false));
		workers.shutdownNow();
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
	 * One resource lane: its queue, limit, and in-flight count. Guarded by the scheduler lock.
	 */
	private static final class Lane {
		private final ExecutionResourceKey key;
		private final PriorityQueue<Task> queue = new PriorityQueue<>(Comparator.comparingLong(Task::sequence));
		private int limit;
		private boolean pinned;
		private int inFlight;
		private int maxObservedConcurrency;

		/**
		 * Creates a lane from its first assignment.
		 *
		 * @param assignment the assignment
		 */
		private Lane(ResourceAssignment assignment) {
			this.key = assignment.key();
			this.limit = assignment.maxConcurrency();
			this.pinned = assignment.pinned();
		}

		/**
		 * Merges another binding's assignment to this lane.
		 *
		 * @param assignment the other assignment
		 */
		private void absorb(ResourceAssignment assignment) {
			if (assignment.pinned() && !pinned) {
				limit = assignment.maxConcurrency();
				pinned = true;
			} else if (assignment.pinned() == pinned) {
				limit = Math.min(limit, assignment.maxConcurrency());
			}
		}

		/**
		 * Returns whether another invocation may start in this lane.
		 *
		 * @return whether the lane has a free permit
		 */
		private boolean hasPermit() {
			return inFlight < limit;
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
	 *
	 * @param sequence submission order, used to dispatch fairly
	 * @param phase the phase the invocation belongs to
	 * @param lane the lane it runs in
	 * @param binding the binding being invoked
	 * @param callable the invocation
	 * @param result the future to complete
	 */
	private record Task(
			long sequence,
			Phase phase,
			Lane lane,
			ImplementationBinding binding,
			Callable<Response> callable,
			CompletableFuture<Response> result) {
	}
}
