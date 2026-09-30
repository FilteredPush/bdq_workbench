/** RetryPolicy.java
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Decides whether and when a failed group invocation is retried by the workbench.
 *
 * <p>Only {@link FailureCategory#TRANSIENT_EXTERNAL} failures are retried up to
 * {@link ExecutionPolicy#maxRetries()} times, and {@link FailureCategory#AMBIGUOUS_EXTERNAL}
 * failures up to {@link ExecutionPolicy#maxAmbiguousRetries()} times (never more than
 * {@code maxRetries}); configuration and internal failures are never retried. The delay before
 * retry {@code n} (1-based) is {@code min(retryMaxDelay, retryBaseDelay * 2^(n-1))}, reduced by a
 * random fraction of up to {@link ExecutionPolicy#retryJitter()} of itself so that retries of
 * many groups do not arrive together.
 */
final class RetryPolicy {

	private static final int MAX_DOUBLINGS = 30;

	private final ExecutionPolicy policy;
	private final DoubleSupplier random;

	/**
	 * Creates a retry policy using a thread-local random source for jitter.
	 *
	 * @param policy the execution policy
	 */
	RetryPolicy(ExecutionPolicy policy) {
		this(policy, () -> ThreadLocalRandom.current().nextDouble());
	}

	/**
	 * Creates a retry policy with an explicit random source, for deterministic tests.
	 *
	 * @param policy the execution policy
	 * @param random supplies values in {@code [0, 1)} used for jitter
	 */
	RetryPolicy(ExecutionPolicy policy, DoubleSupplier random) {
		this.policy = policy;
		this.random = random;
	}

	/**
	 * Returns how many retries a failure of a category allows.
	 *
	 * @param category the failure category
	 * @return the number of retries; 0 if the category is never retried or retries are disabled
	 */
	int maxRetries(FailureCategory category) {
		if (!policy.retriesEnabled()) {
			return 0;
		}
		return switch (category) {
			case TRANSIENT_EXTERNAL -> policy.maxRetries();
			case AMBIGUOUS_EXTERNAL -> Math.min(policy.maxRetries(), policy.maxAmbiguousRetries());
			default -> 0;
		};
	}

	/**
	 * Returns the delay before a retry.
	 *
	 * @param retryNumber which retry this is, starting at 1
	 * @return the jittered delay
	 */
	Duration delayBeforeRetry(int retryNumber) {
		int doublings = Math.min(MAX_DOUBLINGS, Math.max(0, retryNumber - 1));
		long base = policy.retryBaseDelay().toMillis();
		long max = policy.retryMaxDelay().toMillis();
		long exponential = base > (max >> doublings) ? max : Math.min(max, base << doublings);
		double fraction = Math.min(1.0d, Math.max(0.0d, random.getAsDouble()));
		long jittered = Math.round(exponential * (1.0d - policy.retryJitter() * fraction));
		return Duration.ofMillis(Math.max(0L, jittered));
	}
}
