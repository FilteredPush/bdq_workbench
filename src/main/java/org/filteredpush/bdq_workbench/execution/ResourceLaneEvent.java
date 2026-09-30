/** ResourceLaneEvent.java
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

/**
 * A change in a resource lane's state, or a retry decision, reported through
 * {@link ExecutionProgressListener#onResourceLaneEvent}. Events describe resources and tests, never
 * record contents.
 *
 * @param type what happened
 * @param resource the lane's resource key
 * @param testId the test involved, or {@code null} for lane-wide events
 * @param limit the lane's concurrency limit after the event
 * @param attempt for retry events, the attempt number concerned (the original call is attempt 1);
 *     otherwise 0
 * @param detail a short human-readable explanation
 */
public record ResourceLaneEvent(
		Type type,
		ExecutionResourceKey resource,
		String testId,
		int limit,
		int attempt,
		String detail) {

	/**
	 * Kinds of resource lane event.
	 */
	public enum Type {
		/** The lane's concurrency limit was reduced after an external failure. */
		THROTTLED,
		/** The lane's concurrency limit (or the ceiling it may grow to) was raised after successes. */
		CAPACITY_RESTORED,
		/** Consecutive external failures opened the lane's circuit; its queued work is paused. */
		CIRCUIT_OPENED,
		/** The cooldown elapsed; one probe invocation may run. */
		CIRCUIT_HALF_OPEN,
		/** A probe succeeded; the lane resumes, starting at a concurrency of 1. */
		CIRCUIT_CLOSED,
		/**
		 * The circuit opened too many times without a successful probe; remaining work for the lane
		 * is reported as failed external prerequisites without being invoked.
		 */
		LANE_UNAVAILABLE,
		/** A group invocation failed transiently and will be retried after a delay. */
		RETRY_SCHEDULED,
		/** A retried group invocation succeeded. */
		RETRY_SUCCEEDED,
		/** A group invocation failed on its last permitted attempt. */
		RETRY_EXHAUSTED
	}

	/**
	 * Renders the event for logs.
	 *
	 * @return a single-line key=value description
	 */
	@Override
	public String toString() {
		return "event=" + type
				+ " resource=" + resource
				+ (testId == null ? "" : " test=" + testId)
				+ " limit=" + limit
				+ (attempt > 0 ? " attempt=" + attempt : "")
				+ (detail == null || detail.isBlank() ? "" : " detail=\"" + detail + "\"");
	}
}
