/** ExecutionPolicy.java
 *
 * Immutable configuration of how ParallelPhaseExecutionService schedules, throttles, retries, and
 * reuses test invocations: resource lane limits and overrides, adaptive throttling, circuit
 * breaking, workbench-level retries, and PRE_AMENDMENT to POST_AMENDMENT result reuse.
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable execution policy accepted by the policy-aware
 * {@link ParallelPhaseExecutionService} constructor; build one with {@link #builder()} or start
 * from {@link #defaults()}.
 *
 * <p>The global worker count stays a separate constructor argument. Everything here governs how
 * that worker pool is shared:
 * <ul>
 *   <li><b>Resource lanes.</b> Each binding is assigned a resource lane (see
 *   {@link ExecutionResourceClassifier}); a lane never runs more than its limit of invocations at
 *   once, and work waiting on a lane never occupies a worker. Defaults: likely-external lanes
 *   {@value #DEFAULT_EXTERNAL_CONCURRENCY}, unclassified lanes
 *   {@value #DEFAULT_UNCLASSIFIED_CONCURRENCY}, local lanes the whole worker pool (every limit is
 *   capped at the worker count). {@link #overrides()} reclassify, rename the lane of, or limit
 *   individual tests or implementations; {@link #resourceConcurrency()} limits a lane by its key.</li>
 *   <li><b>Adaptive throttling.</b> A likely transient external failure halves a lane's current
 *   limit (to a minimum of 1); every {@value #DEFAULT_SUCCESSES_BEFORE_INCREASE} successes restore
 *   one slot, up to the lane's ceiling.</li>
 *   <li><b>Circuit breaker.</b> {@value #DEFAULT_CIRCUIT_FAILURE_THRESHOLD} consecutive transient
 *   failures open a lane's circuit: its queued work pauses, without holding workers, for a
 *   cooldown (initially {@link #DEFAULT_CIRCUIT_INITIAL_COOLDOWN}, doubling up to
 *   {@link #DEFAULT_CIRCUIT_MAX_COOLDOWN}); then one probe runs. After
 *   {@value #DEFAULT_CIRCUIT_MAX_OPENINGS} openings without a successful probe the lane's
 *   remaining work is reported as {@code EXTERNAL_PREREQUISITES_NOT_MET} without being invoked.</li>
 *   <li><b>Retries.</b> Likely transient failures are retried up to
 *   {@value #DEFAULT_MAX_RETRIES} times (ambiguous external-prerequisite failures up to
 *   {@value #DEFAULT_MAX_AMBIGUOUS_RETRIES} time), with exponential backoff from
 *   {@link #DEFAULT_RETRY_BASE_DELAY} to at most {@link #DEFAULT_RETRY_MAX_DELAY}, reduced by up
 *   to {@value #DEFAULT_RETRY_JITTER} of the delay at random.</li>
 *   <li><b>PRE-to-POST reuse.</b> A POST_AMENDMENT re-run of a PRE_AMENDMENT validation or measure
 *   reuses the successful PRE_AMENDMENT result of an identical invocation instead of invoking the
 *   implementation again.</li>
 * </ul>
 * Each feature can be switched off independently.
 */
public final class ExecutionPolicy {

	/** Default maximum concurrency of a likely-external resource lane. */
	public static final int DEFAULT_EXTERNAL_CONCURRENCY = 2;
	/** Default maximum concurrency of an unclassified resource lane. */
	public static final int DEFAULT_UNCLASSIFIED_CONCURRENCY = 4;
	/** Default number of successes needed to restore one slot of a throttled lane. */
	public static final int DEFAULT_SUCCESSES_BEFORE_INCREASE = 5;
	/**
	 * Default number of consecutive successes, with no external failure ever observed, after which
	 * a lane classified external only by a static hint is allowed to grow to the whole worker pool.
	 */
	public static final int DEFAULT_LOCAL_PROMOTION_SUCCESSES = 50;
	/** Default number of consecutive transient failures that open a lane's circuit. */
	public static final int DEFAULT_CIRCUIT_FAILURE_THRESHOLD = 3;
	/** Default cooldown after a lane's circuit first opens. */
	public static final Duration DEFAULT_CIRCUIT_INITIAL_COOLDOWN = Duration.ofSeconds(5);
	/** Default upper bound of the exponentially increasing circuit cooldown. */
	public static final Duration DEFAULT_CIRCUIT_MAX_COOLDOWN = Duration.ofSeconds(60);
	/** Default number of circuit openings without a successful probe before a lane gives up. */
	public static final int DEFAULT_CIRCUIT_MAX_OPENINGS = 5;
	/** Default maximum number of workbench-level retries of a likely transient failure. */
	public static final int DEFAULT_MAX_RETRIES = 2;
	/** Default maximum number of retries of an ambiguous external-prerequisite failure. */
	public static final int DEFAULT_MAX_AMBIGUOUS_RETRIES = 1;
	/** Default delay before the first retry. */
	public static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofMillis(500);
	/** Default upper bound of the exponentially increasing retry delay. */
	public static final Duration DEFAULT_RETRY_MAX_DELAY = Duration.ofSeconds(8);
	/** Default fraction of a retry delay that jitter may remove. */
	public static final double DEFAULT_RETRY_JITTER = 0.5d;

	private static final ExecutionPolicy DEFAULTS = builder().build();

	private final boolean laneSchedulingEnabled;
	private final int externalConcurrency;
	private final int unclassifiedConcurrency;
	private final int localConcurrency;
	private final Map<String, ResourceOverride> overrides;
	private final Map<String, Integer> resourceConcurrency;
	private final boolean adaptiveThrottlingEnabled;
	private final int successesBeforeIncrease;
	private final int localPromotionSuccesses;
	private final int circuitFailureThreshold;
	private final Duration circuitInitialCooldown;
	private final Duration circuitMaxCooldown;
	private final int circuitMaxOpenings;
	private final boolean retriesEnabled;
	private final int maxRetries;
	private final int maxAmbiguousRetries;
	private final Duration retryBaseDelay;
	private final Duration retryMaxDelay;
	private final double retryJitter;
	private final boolean prePostReuseEnabled;

	/**
	 * Copies a builder's (already validated) settings.
	 *
	 * @param builder the builder to copy
	 */
	private ExecutionPolicy(Builder builder) {
		this.laneSchedulingEnabled = builder.laneSchedulingEnabled;
		this.externalConcurrency = builder.externalConcurrency;
		this.unclassifiedConcurrency = builder.unclassifiedConcurrency;
		this.localConcurrency = builder.localConcurrency;
		this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(builder.overrides));
		this.resourceConcurrency = Collections.unmodifiableMap(new LinkedHashMap<>(builder.resourceConcurrency));
		this.adaptiveThrottlingEnabled = builder.adaptiveThrottlingEnabled;
		this.successesBeforeIncrease = builder.successesBeforeIncrease;
		this.localPromotionSuccesses = builder.localPromotionSuccesses;
		this.circuitFailureThreshold = builder.circuitFailureThreshold;
		this.circuitInitialCooldown = builder.circuitInitialCooldown;
		this.circuitMaxCooldown = builder.circuitMaxCooldown;
		this.circuitMaxOpenings = builder.circuitMaxOpenings;
		this.retriesEnabled = builder.retriesEnabled;
		this.maxRetries = builder.maxRetries;
		this.maxAmbiguousRetries = builder.maxAmbiguousRetries;
		this.retryBaseDelay = builder.retryBaseDelay;
		this.retryMaxDelay = builder.retryMaxDelay;
		this.retryJitter = builder.retryJitter;
		this.prePostReuseEnabled = builder.prePostReuseEnabled;
	}

	/**
	 * Returns the default policy: every feature enabled with the documented default values.
	 *
	 * @return the default policy
	 */
	public static ExecutionPolicy defaults() {
		return DEFAULTS;
	}

	/**
	 * Returns a builder initialized to the defaults.
	 *
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns a builder initialized to this policy's settings.
	 *
	 * @return a new builder carrying this policy's settings
	 */
	public Builder toBuilder() {
		Builder builder = new Builder();
		builder.laneSchedulingEnabled = laneSchedulingEnabled;
		builder.externalConcurrency = externalConcurrency;
		builder.unclassifiedConcurrency = unclassifiedConcurrency;
		builder.localConcurrency = localConcurrency;
		builder.overrides.putAll(overrides);
		builder.resourceConcurrency.putAll(resourceConcurrency);
		builder.adaptiveThrottlingEnabled = adaptiveThrottlingEnabled;
		builder.successesBeforeIncrease = successesBeforeIncrease;
		builder.localPromotionSuccesses = localPromotionSuccesses;
		builder.circuitFailureThreshold = circuitFailureThreshold;
		builder.circuitInitialCooldown = circuitInitialCooldown;
		builder.circuitMaxCooldown = circuitMaxCooldown;
		builder.circuitMaxOpenings = circuitMaxOpenings;
		builder.retriesEnabled = retriesEnabled;
		builder.maxRetries = maxRetries;
		builder.maxAmbiguousRetries = maxAmbiguousRetries;
		builder.retryBaseDelay = retryBaseDelay;
		builder.retryMaxDelay = retryMaxDelay;
		builder.retryJitter = retryJitter;
		builder.prePostReuseEnabled = prePostReuseEnabled;
		return builder;
	}

	/**
	 * Returns whether bindings are assigned to per-resource lanes; when {@code false}, every
	 * binding shares one unrestricted lane and adaptive throttling/circuit breaking do not apply.
	 *
	 * @return whether lane scheduling is enabled
	 */
	public boolean laneSchedulingEnabled() {
		return laneSchedulingEnabled;
	}

	/**
	 * Returns the default maximum concurrency of a likely-external lane.
	 *
	 * @return the external lane limit
	 */
	public int externalConcurrency() {
		return externalConcurrency;
	}

	/**
	 * Returns the default maximum concurrency of an unclassified lane.
	 *
	 * @return the unclassified lane limit
	 */
	public int unclassifiedConcurrency() {
		return unclassifiedConcurrency;
	}

	/**
	 * Returns the default maximum concurrency of a local lane; 0 means the worker count.
	 *
	 * @return the local lane limit, or 0 for the whole worker pool
	 */
	public int localConcurrency() {
		return localConcurrency;
	}

	/**
	 * Returns the per-test/per-implementation overrides, keyed by test ID, full implementation
	 * signature ({@code class#method(types)}), implementation key ({@code class#method}), or
	 * implementation class name. See {@link ExecutionResourceClassifier} for precedence.
	 *
	 * @return the overrides, in configuration order
	 */
	public Map<String, ResourceOverride> overrides() {
		return overrides;
	}

	/**
	 * Returns per-lane concurrency limits keyed by {@link ExecutionResourceKey#id()}; these win
	 * over every other limit for that lane.
	 *
	 * @return the per-lane limits
	 */
	public Map<String, Integer> resourceConcurrency() {
		return resourceConcurrency;
	}

	/**
	 * Returns whether lanes adapt their limits to observed outcomes.
	 *
	 * @return whether adaptive throttling is enabled
	 */
	public boolean adaptiveThrottlingEnabled() {
		return adaptiveThrottlingEnabled;
	}

	/**
	 * Returns how many successes restore one slot of a throttled lane.
	 *
	 * @return the successes needed per additive increase
	 */
	public int successesBeforeIncrease() {
		return successesBeforeIncrease;
	}

	/**
	 * Returns how many consecutive successes, with no external failure observed, let a lane
	 * classified external only by a static hint grow to the worker count; 0 disables this.
	 *
	 * @return the successes needed to relax a hinted external lane
	 */
	public int localPromotionSuccesses() {
		return localPromotionSuccesses;
	}

	/**
	 * Returns how many consecutive transient failures open a lane's circuit; 0 disables the
	 * circuit breaker.
	 *
	 * @return the circuit failure threshold
	 */
	public int circuitFailureThreshold() {
		return circuitFailureThreshold;
	}

	/**
	 * Returns the cooldown after a lane's circuit first opens.
	 *
	 * @return the initial cooldown
	 */
	public Duration circuitInitialCooldown() {
		return circuitInitialCooldown;
	}

	/**
	 * Returns the upper bound of the circuit cooldown.
	 *
	 * @return the maximum cooldown
	 */
	public Duration circuitMaxCooldown() {
		return circuitMaxCooldown;
	}

	/**
	 * Returns how many times a lane's circuit may open without a successful probe before the lane
	 * gives up on its remaining work.
	 *
	 * @return the maximum number of openings
	 */
	public int circuitMaxOpenings() {
		return circuitMaxOpenings;
	}

	/**
	 * Returns whether likely transient failures are retried by the workbench.
	 *
	 * @return whether retries are enabled
	 */
	public boolean retriesEnabled() {
		return retriesEnabled;
	}

	/**
	 * Returns the maximum number of retries of a likely transient failure.
	 *
	 * @return the maximum retries
	 */
	public int maxRetries() {
		return maxRetries;
	}

	/**
	 * Returns the maximum number of retries of an ambiguous external-prerequisite failure.
	 *
	 * @return the maximum ambiguous retries
	 */
	public int maxAmbiguousRetries() {
		return maxAmbiguousRetries;
	}

	/**
	 * Returns the delay before the first retry.
	 *
	 * @return the base retry delay
	 */
	public Duration retryBaseDelay() {
		return retryBaseDelay;
	}

	/**
	 * Returns the upper bound of the retry delay.
	 *
	 * @return the maximum retry delay
	 */
	public Duration retryMaxDelay() {
		return retryMaxDelay;
	}

	/**
	 * Returns the fraction (0 to 1) of a retry delay that jitter may remove.
	 *
	 * @return the jitter fraction
	 */
	public double retryJitter() {
		return retryJitter;
	}

	/**
	 * Returns whether POST_AMENDMENT re-runs reuse identical successful PRE_AMENDMENT results.
	 *
	 * @return whether PRE-to-POST reuse is enabled
	 */
	public boolean prePostReuseEnabled() {
		return prePostReuseEnabled;
	}

	/**
	 * Summarizes the policy for logs.
	 *
	 * @return a one-line description of the policy
	 */
	@Override
	public String toString() {
		return "ExecutionPolicy[lanes=" + laneSchedulingEnabled
				+ ", external=" + externalConcurrency
				+ ", unclassified=" + unclassifiedConcurrency
				+ ", local=" + (localConcurrency == 0 ? "workers" : Integer.toString(localConcurrency))
				+ ", overrides=" + overrides
				+ ", laneLimits=" + resourceConcurrency
				+ ", adaptive=" + adaptiveThrottlingEnabled
				+ ", circuitThreshold=" + circuitFailureThreshold
				+ ", retries=" + (retriesEnabled ? Integer.toString(maxRetries) : "off")
				+ ", reuse=" + prePostReuseEnabled + "]";
	}

	/**
	 * A per-test or per-implementation override of the static classification.
	 *
	 * @param resourceClass the class to assign, or {@code null} to keep the static hint
	 * @param lane an explicit lane name (so several tests can share a lane), or {@code null} to
	 *     derive the key
	 * @param maxConcurrency an explicit concurrency limit, or {@code null} for the class default
	 */
	public record ResourceOverride(ResourceClass resourceClass, String lane, Integer maxConcurrency) {

		/**
		 * Normalizes a blank lane name to {@code null} and validates the limit.
		 *
		 * @throws IllegalArgumentException if {@code maxConcurrency} is less than 1
		 */
		public ResourceOverride {
			lane = lane == null || lane.isBlank() ? null : lane.trim();
			if (maxConcurrency != null && maxConcurrency < 1) {
				throw new IllegalArgumentException("Override maxConcurrency must be >= 1");
			}
		}
	}

	/**
	 * Mutable builder for {@link ExecutionPolicy}; every setter validates its argument.
	 */
	public static final class Builder {
		private boolean laneSchedulingEnabled = true;
		private int externalConcurrency = DEFAULT_EXTERNAL_CONCURRENCY;
		private int unclassifiedConcurrency = DEFAULT_UNCLASSIFIED_CONCURRENCY;
		private int localConcurrency;
		private final Map<String, ResourceOverride> overrides = new LinkedHashMap<>();
		private final Map<String, Integer> resourceConcurrency = new LinkedHashMap<>();
		private boolean adaptiveThrottlingEnabled = true;
		private int successesBeforeIncrease = DEFAULT_SUCCESSES_BEFORE_INCREASE;
		private int localPromotionSuccesses = DEFAULT_LOCAL_PROMOTION_SUCCESSES;
		private int circuitFailureThreshold = DEFAULT_CIRCUIT_FAILURE_THRESHOLD;
		private Duration circuitInitialCooldown = DEFAULT_CIRCUIT_INITIAL_COOLDOWN;
		private Duration circuitMaxCooldown = DEFAULT_CIRCUIT_MAX_COOLDOWN;
		private int circuitMaxOpenings = DEFAULT_CIRCUIT_MAX_OPENINGS;
		private boolean retriesEnabled = true;
		private int maxRetries = DEFAULT_MAX_RETRIES;
		private int maxAmbiguousRetries = DEFAULT_MAX_AMBIGUOUS_RETRIES;
		private Duration retryBaseDelay = DEFAULT_RETRY_BASE_DELAY;
		private Duration retryMaxDelay = DEFAULT_RETRY_MAX_DELAY;
		private double retryJitter = DEFAULT_RETRY_JITTER;
		private boolean prePostReuseEnabled = true;

		/**
		 * Creates a builder initialized to the defaults.
		 */
		private Builder() {
		}

		/**
		 * Enables or disables per-resource lanes.
		 *
		 * @param enabled whether lane scheduling is enabled
		 * @return this builder
		 */
		public Builder laneSchedulingEnabled(boolean enabled) {
			this.laneSchedulingEnabled = enabled;
			return this;
		}

		/**
		 * Sets the default maximum concurrency of likely-external lanes.
		 *
		 * @param value the limit, at least 1
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is less than 1
		 */
		public Builder externalConcurrency(int value) {
			this.externalConcurrency = atLeast(value, 1, "externalConcurrency");
			return this;
		}

		/**
		 * Sets the default maximum concurrency of unclassified lanes.
		 *
		 * @param value the limit, at least 1
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is less than 1
		 */
		public Builder unclassifiedConcurrency(int value) {
			this.unclassifiedConcurrency = atLeast(value, 1, "unclassifiedConcurrency");
			return this;
		}

		/**
		 * Sets the default maximum concurrency of local lanes.
		 *
		 * @param value the limit, or 0 for the whole worker pool
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is negative
		 */
		public Builder localConcurrency(int value) {
			this.localConcurrency = atLeast(value, 0, "localConcurrency");
			return this;
		}

		/**
		 * Adds (or replaces) an override for a test ID, implementation signature, implementation
		 * key, or implementation class.
		 *
		 * @param target the test ID, {@code class#method(types)}, {@code class#method}, or class
		 * @param override the override to apply
		 * @return this builder
		 * @throws IllegalArgumentException if {@code target} is blank
		 */
		public Builder override(String target, ResourceOverride override) {
			if (target == null || target.isBlank()) {
				throw new IllegalArgumentException("Override target must not be blank");
			}
			overrides.put(target.trim(), Objects.requireNonNull(override));
			return this;
		}

		/**
		 * Adds every entry of {@code values} as an override.
		 *
		 * @param values overrides keyed by target
		 * @return this builder
		 */
		public Builder overrides(Map<String, ResourceOverride> values) {
			values.forEach(this::override);
			return this;
		}

		/**
		 * Limits the lane with the given key.
		 *
		 * @param resourceKey the lane's {@link ExecutionResourceKey#id()}
		 * @param maxConcurrency the limit, at least 1
		 * @return this builder
		 * @throws IllegalArgumentException if the key is blank or the limit is less than 1
		 */
		public Builder resourceConcurrency(String resourceKey, int maxConcurrency) {
			if (resourceKey == null || resourceKey.isBlank()) {
				throw new IllegalArgumentException("Resource key must not be blank");
			}
			resourceConcurrency.put(resourceKey.trim(), atLeast(maxConcurrency, 1, "resource concurrency"));
			return this;
		}

		/**
		 * Adds every entry of {@code values} as a per-lane limit.
		 *
		 * @param values limits keyed by lane key
		 * @return this builder
		 */
		public Builder resourceConcurrency(Map<String, Integer> values) {
			values.forEach(this::resourceConcurrency);
			return this;
		}

		/**
		 * Enables or disables adaptive throttling and the circuit breaker.
		 *
		 * @param enabled whether lanes adapt to observed outcomes
		 * @return this builder
		 */
		public Builder adaptiveThrottlingEnabled(boolean enabled) {
			this.adaptiveThrottlingEnabled = enabled;
			return this;
		}

		/**
		 * Sets how many successes restore one slot of a throttled lane.
		 *
		 * @param value the number of successes, at least 1
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is less than 1
		 */
		public Builder successesBeforeIncrease(int value) {
			this.successesBeforeIncrease = atLeast(value, 1, "successesBeforeIncrease");
			return this;
		}

		/**
		 * Sets how many consecutive successes relax a hinted external lane; 0 disables this.
		 *
		 * @param value the number of successes, at least 0
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is negative
		 */
		public Builder localPromotionSuccesses(int value) {
			this.localPromotionSuccesses = atLeast(value, 0, "localPromotionSuccesses");
			return this;
		}

		/**
		 * Sets how many consecutive transient failures open a lane's circuit; 0 disables it.
		 *
		 * @param value the threshold, at least 0
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is negative
		 */
		public Builder circuitFailureThreshold(int value) {
			this.circuitFailureThreshold = atLeast(value, 0, "circuitFailureThreshold");
			return this;
		}

		/**
		 * Sets the initial circuit cooldown.
		 *
		 * @param value the cooldown, not negative
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is negative
		 */
		public Builder circuitInitialCooldown(Duration value) {
			this.circuitInitialCooldown = notNegative(value, "circuitInitialCooldown");
			return this;
		}

		/**
		 * Sets the maximum circuit cooldown.
		 *
		 * @param value the cooldown, not negative
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is negative
		 */
		public Builder circuitMaxCooldown(Duration value) {
			this.circuitMaxCooldown = notNegative(value, "circuitMaxCooldown");
			return this;
		}

		/**
		 * Sets how many circuit openings without a successful probe a lane tolerates.
		 *
		 * @param value the number of openings, at least 1
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is less than 1
		 */
		public Builder circuitMaxOpenings(int value) {
			this.circuitMaxOpenings = atLeast(value, 1, "circuitMaxOpenings");
			return this;
		}

		/**
		 * Enables or disables workbench-level retries.
		 *
		 * @param enabled whether retries are enabled
		 * @return this builder
		 */
		public Builder retriesEnabled(boolean enabled) {
			this.retriesEnabled = enabled;
			return this;
		}

		/**
		 * Sets the maximum number of retries of a likely transient failure.
		 *
		 * @param value the number of retries, at least 0
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is negative
		 */
		public Builder maxRetries(int value) {
			this.maxRetries = atLeast(value, 0, "maxRetries");
			return this;
		}

		/**
		 * Sets the maximum number of retries of an ambiguous external-prerequisite failure.
		 *
		 * @param value the number of retries, at least 0
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is negative
		 */
		public Builder maxAmbiguousRetries(int value) {
			this.maxAmbiguousRetries = atLeast(value, 0, "maxAmbiguousRetries");
			return this;
		}

		/**
		 * Sets the delay before the first retry.
		 *
		 * @param value the delay, not negative
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is negative
		 */
		public Builder retryBaseDelay(Duration value) {
			this.retryBaseDelay = notNegative(value, "retryBaseDelay");
			return this;
		}

		/**
		 * Sets the upper bound of the retry delay.
		 *
		 * @param value the delay, not negative
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is negative
		 */
		public Builder retryMaxDelay(Duration value) {
			this.retryMaxDelay = notNegative(value, "retryMaxDelay");
			return this;
		}

		/**
		 * Sets the fraction of a retry delay that jitter may remove.
		 *
		 * @param value the fraction, between 0 and 1
		 * @return this builder
		 * @throws IllegalArgumentException if {@code value} is outside 0 to 1
		 */
		public Builder retryJitter(double value) {
			if (value < 0.0d || value > 1.0d || Double.isNaN(value)) {
				throw new IllegalArgumentException("retryJitter must be between 0 and 1");
			}
			this.retryJitter = value;
			return this;
		}

		/**
		 * Enables or disables PRE-to-POST result reuse.
		 *
		 * @param enabled whether reuse is enabled
		 * @return this builder
		 */
		public Builder prePostReuseEnabled(boolean enabled) {
			this.prePostReuseEnabled = enabled;
			return this;
		}

		/**
		 * Builds the immutable policy.
		 *
		 * @return the policy
		 * @throws IllegalArgumentException if the maximum circuit cooldown is shorter than the
		 *     initial cooldown or the maximum retry delay shorter than the base delay
		 */
		public ExecutionPolicy build() {
			if (circuitMaxCooldown.compareTo(circuitInitialCooldown) < 0) {
				throw new IllegalArgumentException("circuitMaxCooldown must not be shorter than circuitInitialCooldown");
			}
			if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
				throw new IllegalArgumentException("retryMaxDelay must not be shorter than retryBaseDelay");
			}
			return new ExecutionPolicy(this);
		}

		/**
		 * Validates a lower bound.
		 *
		 * @param value the value
		 * @param minimum the smallest allowed value
		 * @param name the setting name, for the error message
		 * @return {@code value}
		 * @throws IllegalArgumentException if {@code value} is below {@code minimum}
		 */
		private static int atLeast(int value, int minimum, String name) {
			if (value < minimum) {
				throw new IllegalArgumentException(name + " must be >= " + minimum);
			}
			return value;
		}

		/**
		 * Validates a non-negative duration.
		 *
		 * @param value the duration
		 * @param name the setting name, for the error message
		 * @return {@code value}
		 * @throws IllegalArgumentException if {@code value} is null or negative
		 */
		private static Duration notNegative(Duration value, String name) {
			if (value == null || value.isNegative()) {
				throw new IllegalArgumentException(name + " must not be negative");
			}
			return value;
		}
	}
}
