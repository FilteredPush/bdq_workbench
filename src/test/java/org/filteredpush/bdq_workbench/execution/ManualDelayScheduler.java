/** ManualDelayScheduler.java
 *
 * Test timer whose scheduled actions run only when a test tells them to.
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
import java.util.List;

/**
 * A {@link DelayScheduler} that records what was scheduled and runs it only on request, so tests
 * control cooldowns and backoff deterministically.
 */
final class ManualDelayScheduler implements DelayScheduler {

	private final List<Scheduled> pending = new ArrayList<>();
	private final List<Duration> requestedDelays = new ArrayList<>();
	private boolean closed;

	/**
	 * Records an action.
	 *
	 * @param delay the requested delay
	 * @param action the action
	 * @return a handle removing the action
	 */
	@Override
	public synchronized Cancellable schedule(Duration delay, Runnable action) {
		Scheduled scheduled = new Scheduled(delay, action);
		requestedDelays.add(delay);
		if (!closed) {
			pending.add(scheduled);
		}
		return () -> {
			synchronized (ManualDelayScheduler.this) {
				pending.remove(scheduled);
			}
		};
	}

	/**
	 * Discards pending actions.
	 */
	@Override
	public synchronized void close() {
		closed = true;
		pending.clear();
	}

	/**
	 * Returns the delays requested so far, in order.
	 *
	 * @return the delays
	 */
	synchronized List<Duration> requestedDelays() {
		return List.copyOf(requestedDelays);
	}

	/**
	 * Returns how many actions are waiting.
	 *
	 * @return the number of pending actions
	 */
	synchronized int pendingCount() {
		return pending.size();
	}

	/**
	 * Returns whether the scheduler was closed.
	 *
	 * @return whether {@link #close()} was called
	 */
	synchronized boolean isClosed() {
		return closed;
	}

	/**
	 * Runs every pending action (outside this scheduler's monitor).
	 *
	 * @return how many actions ran
	 */
	int runPending() {
		List<Scheduled> due;
		synchronized (this) {
			due = new ArrayList<>(pending);
			pending.clear();
		}
		due.forEach(scheduled -> scheduled.action().run());
		return due.size();
	}

	/**
	 * One scheduled action.
	 *
	 * @param delay the requested delay
	 * @param action the action
	 */
	private record Scheduled(Duration delay, Runnable action) {
	}
}
