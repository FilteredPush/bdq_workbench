/** AdaptiveLaneSchedulerTest.java
 *
 * Tests adaptive throttling, capacity restoration, the circuit breaker, lane unavailability, and
 * cancellation of paused work in the resource lane scheduler, without any network access and
 * with a manually driven timer.
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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class AdaptiveLaneSchedulerTest {

	private static final CanonicalRecord RECORD = new CanonicalRecord("r1", Map.of());
	private static final ImplementationBinding BINDING = ExecutionTestSupport.binding("worms-test", TestType.VALIDATION,
			"org.example.Remote", "validate", Phase.PRE_AMENDMENT, "f");
	private static final ExecutionResourceKey WORMS = ExecutionResourceKey.source("worms");
	private static final ExecutionResourceKey LOCAL = ExecutionResourceKey.local("org.example.Local", "validateNotempty");
	private static final Callable<Response> SUCCESS = () -> ExecutionTestSupport.compliant(RECORD, BINDING);
	private static final Callable<Response> TRANSIENT = () -> ExecutionTestSupport.externalFailure(RECORD, BINDING,
			"Read timed out");
	private static final Callable<Response> CONFIGURATION = () -> ExecutionTestSupport.externalFailure(RECORD, BINDING,
			"Unsupported source authority");

	private final ManualDelayScheduler timer = new ManualDelayScheduler();
	private final List<ResourceLaneEvent> events = Collections.synchronizedList(new ArrayList<>());
	private final ExecutionStatisticsCollector statistics = new ExecutionStatisticsCollector();

	private ResourceLaneScheduler scheduler(ExecutionPolicy policy, int workers) {
		/* Retries are covered by RetrySchedulerTest; here each failure should settle at once. */
		return new ResourceLaneScheduler(workers, policy.toBuilder().retriesEnabled(false).build(), timer,
				(phase, event) -> events.add(event), statistics);
	}

	private static ResourceAssignment external(int limit) {
		return new ResourceAssignment(WORMS, ResourceClass.EXTERNAL, "test", limit, false);
	}

	private static Response run(ResourceLaneScheduler scheduler, ResourceAssignment assignment, Callable<Response> task)
			throws Exception {
		CompletableFuture<Response> result = new CompletableFuture<>();
		scheduler.submit(Phase.PRE_AMENDMENT, assignment, BINDING, task, result);
		return result.get(5, TimeUnit.SECONDS);
	}

	private List<ResourceLaneEvent.Type> eventTypes() {
		synchronized (events) {
			return events.stream().map(ResourceLaneEvent::type).toList();
		}
	}

	@Test
	void transientFailuresHalveTheLimitAndSuccessesRestoreItAdditively() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().circuitFailureThreshold(0).successesBeforeIncrease(2)
				.externalConcurrency(4).build();
		try (ResourceLaneScheduler scheduler = scheduler(policy, 8)) {
			run(scheduler, external(4), TRANSIENT);
			assertThat(scheduler.currentLimit(WORMS)).isEqualTo(2);
			run(scheduler, external(4), TRANSIENT);
			run(scheduler, external(4), TRANSIENT);
			assertThat(scheduler.currentLimit(WORMS)).as("never below 1").isEqualTo(1);

			for (int i = 0; i < 4; i++) {
				run(scheduler, external(4), SUCCESS);
			}
			assertThat(scheduler.currentLimit(WORMS)).isEqualTo(3);
			for (int i = 0; i < 10; i++) {
				run(scheduler, external(4), SUCCESS);
			}
			assertThat(scheduler.currentLimit(WORMS)).as("capped at the lane's ceiling").isEqualTo(4);
		}
		assertThat(eventTypes()).contains(ResourceLaneEvent.Type.THROTTLED, ResourceLaneEvent.Type.CAPACITY_RESTORED);
		assertThat(statistics.snapshot().resource(WORMS).orElseThrow().externalFailures()).isEqualTo(3);
	}

	@Test
	void withoutLanesTheSharedLaneIsNeverThrottledOrOpened() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().laneSchedulingEnabled(false).circuitFailureThreshold(1).build();
		ResourceAssignment shared = new ResourceAssignment(ExecutionResourceKey.UNRESTRICTED, ResourceClass.UNCLASSIFIED,
				"lane scheduling disabled", 4, true);
		try (ResourceLaneScheduler scheduler = scheduler(policy, 4)) {
			for (int i = 0; i < 5; i++) {
				run(scheduler, shared, TRANSIENT);
			}
			assertThat(scheduler.currentLimit(ExecutionResourceKey.UNRESTRICTED)).isEqualTo(4);
			assertThat(scheduler.circuitState(ExecutionResourceKey.UNRESTRICTED))
					.isEqualTo(ResourceLaneScheduler.CircuitState.CLOSED);
		}
		assertThat(events).isEmpty();
		assertThat(statistics.snapshot().resource(ExecutionResourceKey.UNRESTRICTED).orElseThrow().externalFailures())
				.as("failures are still counted").isEqualTo(5);
	}

	@Test
	void configurationFailuresNeitherThrottleNorOpenTheCircuit() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().circuitFailureThreshold(1).build();
		try (ResourceLaneScheduler scheduler = scheduler(policy, 4)) {
			for (int i = 0; i < 5; i++) {
				run(scheduler, external(2), CONFIGURATION);
			}
			assertThat(scheduler.currentLimit(WORMS)).isEqualTo(2);
			assertThat(scheduler.circuitState(WORMS)).isEqualTo(ResourceLaneScheduler.CircuitState.CLOSED);
		}
		assertThat(events).isEmpty();
	}

	@Test
	void anUnclassifiedLaneThatFailsLikeAnExternalServiceIsCappedAtTheExternalLimit() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().circuitFailureThreshold(0).successesBeforeIncrease(1).build();
		ResourceAssignment unclassified = new ResourceAssignment(WORMS, ResourceClass.UNCLASSIFIED, "test", 8, false);
		try (ResourceLaneScheduler scheduler = scheduler(policy, 8)) {
			run(scheduler, unclassified, TRANSIENT);
			for (int i = 0; i < 20; i++) {
				run(scheduler, unclassified, SUCCESS);
			}
			assertThat(scheduler.currentLimit(WORMS)).isEqualTo(ExecutionPolicy.DEFAULT_EXTERNAL_CONCURRENCY);
		}
	}

	@Test
	void aHintedExternalLaneWithoutFailuresGrowsTowardsTheLocalLimit() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().localPromotionSuccesses(3).successesBeforeIncrease(1).build();
		try (ResourceLaneScheduler scheduler = scheduler(policy, 6)) {
			for (int i = 0; i < 3; i++) {
				run(scheduler, external(2), SUCCESS);
			}
			assertThat(scheduler.currentLimit(WORMS)).isEqualTo(3);
			for (int i = 0; i < 10; i++) {
				run(scheduler, external(2), SUCCESS);
			}
			assertThat(scheduler.currentLimit(WORMS)).isEqualTo(6);
		}
	}

	@Test
	void pinnedLanesAreNotPromoted() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().localPromotionSuccesses(1).successesBeforeIncrease(1).build();
		ResourceAssignment pinned = new ResourceAssignment(WORMS, ResourceClass.EXTERNAL, "configured", 2, true);
		try (ResourceLaneScheduler scheduler = scheduler(policy, 6)) {
			for (int i = 0; i < 10; i++) {
				run(scheduler, pinned, SUCCESS);
			}
			assertThat(scheduler.currentLimit(WORMS)).isEqualTo(2);
		}
	}

	@Test
	void repeatedFailuresOpenTheCircuitPausingOnlyThatLaneUntilASuccessfulProbe() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().circuitFailureThreshold(3).build();
		AtomicInteger remoteCalls = new AtomicInteger();
		try (ResourceLaneScheduler scheduler = scheduler(policy, 2)) {
			for (int i = 0; i < 3; i++) {
				run(scheduler, external(1), TRANSIENT);
			}
			assertThat(scheduler.circuitState(WORMS)).isEqualTo(ResourceLaneScheduler.CircuitState.OPEN);
			assertThat(timer.requestedDelays()).containsExactly(ExecutionPolicy.DEFAULT_CIRCUIT_INITIAL_COOLDOWN);

			List<CompletableFuture<Response>> paused = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				CompletableFuture<Response> result = new CompletableFuture<>();
				paused.add(result);
				scheduler.submit(Phase.PRE_AMENDMENT, external(1), BINDING, () -> {
					remoteCalls.incrementAndGet();
					return ExecutionTestSupport.compliant(RECORD, BINDING);
				}, result);
			}
			Response local = run(scheduler, new ResourceAssignment(LOCAL, ResourceClass.LOCAL, "test", 2, false), SUCCESS);
			assertThat(local.status()).as("local work continues while the circuit is open").isNotNull();
			Thread.sleep(50);
			assertThat(remoteCalls.get()).as("paused work is not invoked").isZero();
			assertThat(paused).noneMatch(CompletableFuture::isDone);

			timer.runPending();
			CompletableFuture.allOf(paused.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

			assertThat(remoteCalls.get()).isEqualTo(3);
			assertThat(scheduler.circuitState(WORMS)).isEqualTo(ResourceLaneScheduler.CircuitState.CLOSED);
			assertThat(scheduler.currentLimit(WORMS)).isEqualTo(1);
		}
		assertThat(eventTypes()).containsSubsequence(ResourceLaneEvent.Type.CIRCUIT_OPENED,
				ResourceLaneEvent.Type.CIRCUIT_HALF_OPEN, ResourceLaneEvent.Type.CIRCUIT_CLOSED);
		assertThat(statistics.snapshot().resource(WORMS).orElseThrow().circuitOpenings()).isEqualTo(1);
	}

	@Test
	void aFailedProbeReopensWithABoundedDoubledCooldownAndEventuallyMakesTheLaneUnavailable() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().circuitFailureThreshold(1).circuitMaxOpenings(3)
				.circuitInitialCooldown(Duration.ofSeconds(5)).circuitMaxCooldown(Duration.ofSeconds(8)).build();
		AtomicInteger calls = new AtomicInteger();
		try (ResourceLaneScheduler scheduler = scheduler(policy, 2)) {
			run(scheduler, external(1), TRANSIENT);
			List<CompletableFuture<Response>> queued = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				CompletableFuture<Response> result = new CompletableFuture<>();
				queued.add(result);
				scheduler.submit(Phase.PRE_AMENDMENT, external(1), BINDING, () -> {
					calls.incrementAndGet();
					return TRANSIENT.call();
				}, detail -> ExecutionTestSupport.externalFailure(RECORD, BINDING, detail), result);
			}
			for (int probe = 0; probe < 3; probe++) {
				assertThat(timer.runPending()).isEqualTo(1);
				queued.get(probe).get(5, TimeUnit.SECONDS);
			}
			CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

			assertThat(timer.requestedDelays()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(8),
					Duration.ofSeconds(8));
			assertThat(calls.get()).as("only the three probes were invoked").isEqualTo(3);
			assertThat(scheduler.circuitState(WORMS)).isEqualTo(ResourceLaneScheduler.CircuitState.UNAVAILABLE);
			Response skipped = queued.get(4).get();
			assertThat(skipped.responseStatus()).isEqualTo("EXTERNAL_PREREQUISITES_NOT_MET");
			assertThat(skipped.comment()).contains("source:worms", "unavailable", "Read timed out");
		}
		assertThat(eventTypes()).contains(ResourceLaneEvent.Type.LANE_UNAVAILABLE);
		assertThat(statistics.snapshot().resource(WORMS).orElseThrow().skippedCalls()).isEqualTo(2);
	}

	@Test
	void closingCancelsPausedWorkAndPendingCooldowns() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().circuitFailureThreshold(1).build();
		AtomicInteger calls = new AtomicInteger();
		ResourceLaneScheduler scheduler = scheduler(policy, 2);
		run(scheduler, external(1), TRANSIENT);
		CompletableFuture<Response> paused = new CompletableFuture<>();
		scheduler.submit(Phase.PRE_AMENDMENT, external(1), BINDING, () -> {
			calls.incrementAndGet();
			return SUCCESS.call();
		}, paused);
		assertThat(timer.pendingCount()).isEqualTo(1);

		scheduler.close();

		assertThat(paused.isCancelled()).isTrue();
		assertThat(timer.isClosed()).isTrue();
		assertThat(timer.runPending()).isZero();
		assertThat(calls.get()).isZero();
	}

	@Test
	void adaptationCanBeSwitchedOff() throws Exception {
		ExecutionPolicy policy = ExecutionPolicy.builder().adaptiveThrottlingEnabled(false).circuitFailureThreshold(1).build();
		try (ResourceLaneScheduler scheduler = scheduler(policy, 4)) {
			for (int i = 0; i < 5; i++) {
				run(scheduler, external(2), TRANSIENT);
			}
			assertThat(scheduler.currentLimit(WORMS)).isEqualTo(2);
			assertThat(scheduler.circuitState(WORMS)).isEqualTo(ResourceLaneScheduler.CircuitState.CLOSED);
		}
		assertThat(statistics.snapshot().resource(WORMS).orElseThrow().externalFailures()).isEqualTo(5);
	}
}
