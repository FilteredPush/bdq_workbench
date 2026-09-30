/** DelayScheduler.java
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs actions after a delay, for circuit cooldowns and retry backoff, without ever sleeping on a
 * worker thread. Injectable so tests can control time instead of waiting for it.
 */
interface DelayScheduler extends AutoCloseable {

	/**
	 * Schedules an action.
	 *
	 * @param delay how long to wait; zero or negative runs it as soon as possible
	 * @param action the action; it must be quick, since it runs on the scheduler's timer
	 * @return a handle that can cancel the action before it runs
	 */
	Cancellable schedule(Duration delay, Runnable action);

	/**
	 * Cancels everything pending and releases the scheduler's resources.
	 */
	@Override
	void close();

	/**
	 * A handle to a scheduled action.
	 */
	interface Cancellable {
		/**
		 * Cancels the action if it has not run yet.
		 */
		void cancel();
	}

	/**
	 * Creates a scheduler backed by one daemon timer thread.
	 *
	 * @return a new scheduler; close it when the run ends
	 */
	static DelayScheduler newDefault() {
		ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
				ResourceLaneScheduler.namedThreads("bdq-lane-timer"));
		return new DelayScheduler() {
			/**
			 * Schedules the action on the timer thread.
			 *
			 * @param delay how long to wait
			 * @param action the action
			 * @return a handle cancelling the scheduled future
			 */
			@Override
			public Cancellable schedule(Duration delay, Runnable action) {
				ScheduledFuture<?> future = timer.schedule(action, Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
				return () -> future.cancel(false);
			}

			/**
			 * Shuts the timer down, discarding pending actions.
			 */
			@Override
			public void close() {
				timer.shutdownNow();
			}
		};
	}
}
