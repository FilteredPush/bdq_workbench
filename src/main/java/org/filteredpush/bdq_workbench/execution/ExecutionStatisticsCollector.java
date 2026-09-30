/** ExecutionStatisticsCollector.java
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe, run-scoped accumulator behind {@link ExecutionRunStatistics}.
 */
final class ExecutionStatisticsCollector {

	private final Map<String, Counters> resources = new LinkedHashMap<>();
	private final Map<String, Counters> tests = new LinkedHashMap<>();

	/**
	 * Records that an invocation started.
	 *
	 * @param resource the lane
	 * @param testId the test
	 */
	synchronized void started(ExecutionResourceKey resource, String testId) {
		for (Counters counters : both(resource, testId)) {
			counters.invocations++;
			counters.inFlight++;
			counters.maxConcurrency = Math.max(counters.maxConcurrency, counters.inFlight);
		}
	}

	/**
	 * Records that an invocation finished.
	 *
	 * @param resource the lane
	 * @param testId the test
	 */
	synchronized void finished(ExecutionResourceKey resource, String testId) {
		for (Counters counters : both(resource, testId)) {
			counters.inFlight = Math.max(0, counters.inFlight - 1);
		}
	}

	/**
	 * Records a transient or ambiguous external failure.
	 *
	 * @param resource the lane
	 * @param testId the test
	 */
	synchronized void externalFailure(ExecutionResourceKey resource, String testId) {
		both(resource, testId).forEach(counters -> counters.externalFailures++);
	}

	/**
	 * Records a scheduled retry.
	 *
	 * @param resource the lane
	 * @param testId the test
	 */
	synchronized void retryScheduled(ExecutionResourceKey resource, String testId) {
		both(resource, testId).forEach(counters -> counters.retries++);
	}

	/**
	 * Records a group invocation that succeeded on a retry.
	 *
	 * @param resource the lane
	 * @param testId the test
	 */
	synchronized void retryRecovered(ExecutionResourceKey resource, String testId) {
		both(resource, testId).forEach(counters -> counters.recoveredRetries++);
	}

	/**
	 * Records a group invocation whose retries were exhausted.
	 *
	 * @param resource the lane
	 * @param testId the test
	 */
	synchronized void retryExhausted(ExecutionResourceKey resource, String testId) {
		both(resource, testId).forEach(counters -> counters.exhaustedRetries++);
	}

	/**
	 * Records that a lane's circuit opened.
	 *
	 * @param resource the lane
	 */
	synchronized void circuitOpened(ExecutionResourceKey resource) {
		resources.computeIfAbsent(resource.id(), key -> new Counters()).circuitOpenings++;
	}

	/**
	 * Records an invocation avoided by reusing an earlier result.
	 *
	 * @param resource the lane the invocation would have used
	 * @param testId the test
	 */
	synchronized void reused(ExecutionResourceKey resource, String testId) {
		both(resource, testId).forEach(counters -> counters.reusedCalls++);
	}

	/**
	 * Records an invocation skipped because its lane was unavailable.
	 *
	 * @param resource the lane
	 * @param testId the test
	 */
	synchronized void skipped(ExecutionResourceKey resource, String testId) {
		both(resource, testId).forEach(counters -> counters.skippedCalls++);
	}

	/**
	 * Takes an immutable snapshot.
	 *
	 * @return the statistics so far
	 */
	synchronized ExecutionRunStatistics snapshot() {
		return new ExecutionRunStatistics(entries(resources), entries(tests));
	}

	/**
	 * Returns the counters for a lane and a test, creating them on first use.
	 *
	 * @param resource the lane
	 * @param testId the test
	 * @return the two counters
	 */
	private List<Counters> both(ExecutionResourceKey resource, String testId) {
		return List.of(
				resources.computeIfAbsent(resource.id(), key -> new Counters()),
				tests.computeIfAbsent(testId == null ? "" : testId, key -> new Counters()));
	}

	/**
	 * Converts counters to entries.
	 *
	 * @param counters the counters by key
	 * @return the entries, in insertion order
	 */
	private static List<ExecutionRunStatistics.Entry> entries(Map<String, Counters> counters) {
		return counters.entrySet().stream()
				.map(entry -> entry.getValue().toEntry(entry.getKey()))
				.toList();
	}

	/**
	 * Mutable counters for one key; guarded by the collector's monitor.
	 */
	private static final class Counters {
		private long invocations;
		private int inFlight;
		private int maxConcurrency;
		private long externalFailures;
		private long retries;
		private long recoveredRetries;
		private long exhaustedRetries;
		private long circuitOpenings;
		private long reusedCalls;
		private long skippedCalls;

		/**
		 * Converts to an immutable entry.
		 *
		 * @param key the resource key or test ID
		 * @return the entry
		 */
		private ExecutionRunStatistics.Entry toEntry(String key) {
			return new ExecutionRunStatistics.Entry(key, invocations, maxConcurrency, externalFailures, retries,
					recoveredRetries, exhaustedRetries, circuitOpenings, reusedCalls, skippedCalls);
		}
	}
}
