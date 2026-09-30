/** ResourceLaneSchedulerTest.java
 *
 * Tests per-lane concurrency limits, lane independence, fair dispatch, and cancellation of the
 * resource lane scheduler.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class ResourceLaneSchedulerTest {

	private static final CanonicalRecord RECORD = new CanonicalRecord("r1", Map.of());
	private static final ImplementationBinding BINDING = ExecutionTestSupport.binding("t", TestType.VALIDATION,
			"org.example.Impl", "validate", Phase.PRE_AMENDMENT, "f");

	private static ResourceAssignment lane(String name, int limit) {
		return new ResourceAssignment(ExecutionResourceKey.lane(name), ResourceClass.EXTERNAL, "test", limit, true);
	}

	@Test
	void laneNeverRunsMoreThanItsLimitEvenWithFreeWorkers() throws Exception {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maxRunning = new AtomicInteger();
		List<CompletableFuture<Response>> results = new ArrayList<>();
		try (ResourceLaneScheduler scheduler = new ResourceLaneScheduler(8)) {
			for (int i = 0; i < 12; i++) {
				CompletableFuture<Response> result = new CompletableFuture<>();
				results.add(result);
				scheduler.submit(Phase.PRE_AMENDMENT, lane("external", 2), BINDING, () -> {
					maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
					Thread.sleep(20);
					running.decrementAndGet();
					return ExecutionTestSupport.compliant(RECORD, BINDING);
				}, result);
			}
			CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
			assertThat(maxRunning.get()).isEqualTo(2);
			assertThat(scheduler.maxObservedConcurrency()).containsEntry(ExecutionResourceKey.lane("external"), 2);
		}
	}

	@Test
	void otherLanesKeepRunningWhileOneLaneIsSaturated() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		try (ResourceLaneScheduler scheduler = new ResourceLaneScheduler(4)) {
			List<CompletableFuture<Response>> blocked = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				CompletableFuture<Response> result = new CompletableFuture<>();
				blocked.add(result);
				scheduler.submit(Phase.PRE_AMENDMENT, lane("slow", 1), BINDING, () -> {
					release.await(10, TimeUnit.SECONDS);
					return ExecutionTestSupport.compliant(RECORD, BINDING);
				}, result);
			}
			List<CompletableFuture<Response>> local = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				CompletableFuture<Response> result = new CompletableFuture<>();
				local.add(result);
				scheduler.submit(Phase.PRE_AMENDMENT,
						new ResourceAssignment(ExecutionResourceKey.local("c", "m"), ResourceClass.LOCAL, "test", 4, false),
						BINDING, () -> ExecutionTestSupport.compliant(RECORD, BINDING), result);
			}

			CompletableFuture.allOf(local.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
			assertThat(blocked).allMatch(future -> !future.isDone());
			release.countDown();
			CompletableFuture.allOf(blocked.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void closeCancelsQueuedWorkAndInterruptsInFlightWork() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicInteger invocations = new AtomicInteger();
		CompletableFuture<Response> inFlight = new CompletableFuture<>();
		CompletableFuture<Response> queued = new CompletableFuture<>();
		ResourceLaneScheduler scheduler = new ResourceLaneScheduler(2);
		scheduler.submit(Phase.PRE_AMENDMENT, lane("one", 1), BINDING, () -> {
			invocations.incrementAndGet();
			started.countDown();
			try {
				Thread.sleep(10_000L);
			} catch (InterruptedException e) {
				interrupted.set(true);
				throw e;
			}
			return ExecutionTestSupport.compliant(RECORD, BINDING);
		}, inFlight);
		scheduler.submit(Phase.PRE_AMENDMENT, lane("one", 1), BINDING, () -> {
			invocations.incrementAndGet();
			return ExecutionTestSupport.compliant(RECORD, BINDING);
		}, queued);
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		scheduler.close();

		assertThat(queued.isCancelled()).isTrue();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!interrupted.get() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(interrupted.get()).isTrue();
		assertThat(invocations.get()).isEqualTo(1);
		CompletableFuture<Response> late = new CompletableFuture<>();
		scheduler.submit(Phase.PRE_AMENDMENT, lane("one", 1), BINDING, () -> ExecutionTestSupport.compliant(RECORD, BINDING), late);
		assertThat(late.isCancelled()).isTrue();
	}

	@Test
	void dispatchesTheEarliestSubmittedEligibleWork() throws Exception {
		List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
		CountDownLatch gate = new CountDownLatch(1);
		List<CompletableFuture<Response>> results = new ArrayList<>();
		try (ResourceLaneScheduler scheduler = new ResourceLaneScheduler(1)) {
			CompletableFuture<Response> blocker = new CompletableFuture<>();
			results.add(blocker);
			scheduler.submit(Phase.PRE_AMENDMENT, lane("x", 1), BINDING, () -> {
				gate.await(5, TimeUnit.SECONDS);
				return ExecutionTestSupport.compliant(RECORD, BINDING);
			}, blocker);
			for (String name : List.of("a1", "b1", "a2", "b2")) {
				CompletableFuture<Response> result = new CompletableFuture<>();
				results.add(result);
				scheduler.submit(Phase.PRE_AMENDMENT, lane(name.substring(0, 1), 1), BINDING, () -> {
					order.add(name);
					return ExecutionTestSupport.compliant(RECORD, BINDING);
				}, result);
			}
			gate.countDown();
			CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
		}
		assertThat(order).containsExactly("a1", "b1", "a2", "b2");
	}
}
