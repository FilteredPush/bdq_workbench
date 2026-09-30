/** ResourceLaneExecutionTest.java
 *
 * Service-level tests of resource lanes: per-resource concurrency limits, shared lanes, lane
 * independence, and configuration overrides.
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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class ResourceLaneExecutionTest {

	private static final ExecutionPolicy NO_REUSE = ExecutionPolicy.builder().prePostReuseEnabled(false).build();

	@Test
	void testsSharingASourceAuthorityShareOneLaneWhileLocalWorkUsesTheRestOfThePool() {
		AtomicInteger worms = new AtomicInteger();
		AtomicInteger maxWorms = new AtomicInteger();
		AtomicInteger local = new AtomicInteger();
		AtomicInteger maxLocal = new AtomicInteger();
		AtomicBoolean overlapped = new AtomicBoolean();
		ExecutionAdapter adapter = (record, binding, implementation) -> {
			boolean external = binding.testId().startsWith("E");
			AtomicInteger counter = external ? worms : local;
			(external ? maxWorms : maxLocal).accumulateAndGet(counter.incrementAndGet(), Math::max);
			if (worms.get() > 0 && local.get() > 0) {
				overlapped.set(true);
			}
			try {
				Thread.sleep(25);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				counter.decrementAndGet();
			}
			return ExecutionTestSupport.compliant(record, binding);
		};
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(6, adapter, null, true, NO_REUSE);
		List<ImplementationBinding> bindings = List.of(
				ExecutionTestSupport.withParameter(ExecutionTestSupport.binding("E1", TestType.VALIDATION,
						"org.example.SciNameDQ", "validationScientificnameFound", Phase.PRE_AMENDMENT, "f"),
						"bdq:sourceAuthority", "WoRMS"),
				ExecutionTestSupport.withParameter(ExecutionTestSupport.binding("E2", TestType.VALIDATION,
						"org.example.GeoDQ", "validationCoordinatesTerrestrialmarine", Phase.PRE_AMENDMENT, "f"),
						"bdq:taxonIsMarine", "WoRMS"),
				ExecutionTestSupport.binding("L", TestType.VALIDATION, "org.example.Local", "validationFNotempty",
						Phase.PRE_AMENDMENT, "f"));

		List<Response> responses = service.execute(ExecutionTestSupport.distinctDataset("f", 8), bindings, List.of());

		assertThat(maxWorms.get()).as("the shared WoRMS lane never exceeds the external default").isLessThanOrEqualTo(2);
		assertThat(maxLocal.get()).as("local work is not held to the external limit").isGreaterThan(2);
		assertThat(overlapped.get()).as("local work runs while the external lane is busy").isTrue();
		assertThat(responses.stream().filter(r -> r.phase() == Phase.PRE_AMENDMENT)).hasSize(24);
	}

	@Test
	void configuredResourceLimitSerializesALane() {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maxRunning = new AtomicInteger();
		ExecutionAdapter adapter = (record, binding, implementation) -> {
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				running.decrementAndGet();
			}
			return ExecutionTestSupport.compliant(record, binding);
		};
		ExecutionPolicy policy = NO_REUSE.toBuilder()
				.override("org.example.Remote", new ExecutionPolicy.ResourceOverride(ResourceClass.EXTERNAL, "remote", null))
				.resourceConcurrency("lane:remote", 1)
				.build();
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(4, adapter, null, true, policy);
		List<ImplementationBinding> bindings = List.of(
				ExecutionTestSupport.binding("A", TestType.VALIDATION, "org.example.Remote", "validationA", Phase.PRE_AMENDMENT, "f"),
				ExecutionTestSupport.binding("B", TestType.VALIDATION, "org.example.Remote", "validationB", Phase.PRE_AMENDMENT, "f"));

		service.execute(ExecutionTestSupport.distinctDataset("f", 6), bindings, List.of());

		assertThat(maxRunning.get()).isEqualTo(1);
	}

	@Test
	void disablingLanesRestoresWorkerCountConcurrencyForEveryBinding() {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maxRunning = new AtomicInteger();
		java.util.concurrent.CountDownLatch allStarted = new java.util.concurrent.CountDownLatch(4);
		ExecutionAdapter adapter = (record, binding, implementation) -> {
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			allStarted.countDown();
			try {
				allStarted.await(5, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				running.decrementAndGet();
			}
			return ExecutionTestSupport.compliant(record, binding);
		};
		ExecutionPolicy policy = NO_REUSE.toBuilder().laneSchedulingEnabled(false).build();
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(4, adapter, null, true, policy);
		ImplementationBinding external = ExecutionTestSupport.withParameter(ExecutionTestSupport.binding("E",
				TestType.VALIDATION, "org.example.SciNameDQ", "validationA", Phase.PRE_AMENDMENT, "f"),
				"bdq:sourceAuthority", "WoRMS");

		service.execute(ExecutionTestSupport.distinctDataset("f", 4), List.of(external), List.of());

		assertThat(maxRunning.get()).isEqualTo(4);
	}
}
