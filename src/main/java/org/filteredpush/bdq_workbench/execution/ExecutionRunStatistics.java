/** ExecutionRunStatistics.java
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

import java.util.List;
import java.util.Optional;

/**
 * Per-resource and per-test invocation statistics for one execution run: what the resource lanes
 * did, for tuning the {@link ExecutionPolicy} and for diagnosing external failures. Contains no
 * record data.
 *
 * @param resources one entry per resource lane, in the order lanes were first used
 * @param tests one entry per test ID, in the order tests were first invoked
 */
public record ExecutionRunStatistics(List<Entry> resources, List<Entry> tests) {

	/**
	 * Copies the lists.
	 *
	 * @param resources the resource entries
	 * @param tests the test entries
	 */
	public ExecutionRunStatistics {
		resources = resources == null ? List.of() : List.copyOf(resources);
		tests = tests == null ? List.of() : List.copyOf(tests);
	}

	/**
	 * Returns the entry for a resource lane.
	 *
	 * @param key the lane's resource key
	 * @return the entry, if the lane was used
	 */
	public Optional<Entry> resource(ExecutionResourceKey key) {
		return resources.stream().filter(entry -> entry.key().equals(key.id())).findFirst();
	}

	/**
	 * Returns the entry for a test.
	 *
	 * @param testId the test ID
	 * @return the entry, if the test was invoked or reused
	 */
	public Optional<Entry> test(String testId) {
		return tests.stream().filter(entry -> entry.key().equals(testId)).findFirst();
	}

	/**
	 * Renders a multi-line summary for logs.
	 *
	 * @return one line per resource lane, then one per test
	 */
	public String describe() {
		StringBuilder text = new StringBuilder("Execution statistics");
		resources.forEach(entry -> text.append(System.lineSeparator()).append("  resource ").append(entry));
		tests.forEach(entry -> text.append(System.lineSeparator()).append("  test ").append(entry));
		return text.toString();
	}

	/**
	 * Counters for one resource lane or one test.
	 *
	 * @param key the resource key or test ID
	 * @param invocations real invocations started, retries included
	 * @param maxConcurrency the most invocations observed running at once
	 * @param externalFailures invocations whose response was a transient or ambiguous external
	 *     failure
	 * @param retries retries scheduled
	 * @param recoveredRetries group invocations that failed and then succeeded on a retry
	 * @param exhaustedRetries group invocations that still failed after their last retry
	 * @param circuitOpenings times the lane's circuit opened (zero for tests)
	 * @param reusedCalls POST_AMENDMENT invocations avoided by reusing a PRE_AMENDMENT result
	 * @param skippedCalls invocations not made because the lane was unavailable
	 */
	public record Entry(
			String key,
			long invocations,
			int maxConcurrency,
			long externalFailures,
			long retries,
			long recoveredRetries,
			long exhaustedRetries,
			long circuitOpenings,
			long reusedCalls,
			long skippedCalls) {

		/**
		 * Renders the counters for logs.
		 *
		 * @return a single-line key=value description
		 */
		@Override
		public String toString() {
			return key + " invocations=" + invocations + " maxConcurrency=" + maxConcurrency
					+ " externalFailures=" + externalFailures + " retries=" + retries
					+ " recoveredRetries=" + recoveredRetries + " exhaustedRetries=" + exhaustedRetries
					+ " circuitOpenings=" + circuitOpenings + " reused=" + reusedCalls + " skipped=" + skippedCalls;
		}
	}
}
