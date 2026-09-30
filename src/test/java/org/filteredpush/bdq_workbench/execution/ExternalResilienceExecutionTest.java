/** ExternalResilienceExecutionTest.java
 *
 * Service-level tests of runtime adaptation: a failing external lane opens its circuit while
 * local work continues, recovers through a half-open probe, reports lane events and run
 * statistics, and can be cancelled promptly while paused.
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class ExternalResilienceExecutionTest {

	private static final ImplementationBinding WORMS = ExecutionTestSupport.withParameter(
			ExecutionTestSupport.binding("worms", TestType.VALIDATION, "org.example.GeoDQ",
					"validationCoordinatesTerrestrialmarine", Phase.PRE_AMENDMENT, "f"),
			"bdq:taxonIsMarine", "WoRMS");
	private static final ImplementationBinding LOCAL = ExecutionTestSupport.binding("local", TestType.VALIDATION,
			"org.example.Local", "validationFNotempty", Phase.PRE_AMENDMENT, "f");

	@Test
	void circuitOpensAndRecoversWhileLocalWorkContinues() {
		AtomicInteger wormsCalls = new AtomicInteger();
		List<ResourceLaneEvent> events = Collections.synchronizedList(new ArrayList<>());
		AtomicReference<ExecutionRunStatistics> reported = new AtomicReference<>();
		ExecutionAdapter adapter = (record, binding, implementation) -> {
			if (binding.testId().equals("worms") && wormsCalls.incrementAndGet() <= 3) {
				return ExecutionTestSupport.externalFailure(record, binding, "Read timed out");
			}
			return ExecutionTestSupport.compliant(record, binding);
		};
		ExecutionPolicy policy = ExecutionPolicy.builder()
				.prePostReuseEnabled(false)
				.retriesEnabled(false)
				.circuitFailureThreshold(3)
				.circuitInitialCooldown(Duration.ofMillis(20))
				.build();
		ExecutionProgressListener listener = new ExecutionProgressListener() {
			@Override
			public void onResourceLaneEvent(Phase phase, ResourceLaneEvent event) {
				events.add(event);
			}

			@Override
			public void onExecutionStatistics(ExecutionRunStatistics statistics) {
				reported.set(statistics);
			}
		};
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(4, adapter, listener, true, policy);

		List<Response> responses = service.execute(ExecutionTestSupport.distinctDataset("f", 6), List.of(WORMS, LOCAL),
				List.of());

		List<Response> pre = responses.stream().filter(r -> r.phase() == Phase.PRE_AMENDMENT).toList();
		assertThat(pre.stream().filter(r -> r.testId().equals("local")))
				.hasSize(6)
				.allMatch(r -> "COMPLIANT".equals(r.responseResult()));
		assertThat(pre.stream().filter(r -> r.testId().equals("worms")).map(Response::responseStatus))
				.containsExactlyInAnyOrder("EXTERNAL_PREREQUISITES_NOT_MET", "EXTERNAL_PREREQUISITES_NOT_MET",
						"EXTERNAL_PREREQUISITES_NOT_MET", "RUN_HAS_RESULT", "RUN_HAS_RESULT", "RUN_HAS_RESULT");
		synchronized (events) {
			assertThat(events.stream().map(ResourceLaneEvent::type)).containsSubsequence(
					ResourceLaneEvent.Type.THROTTLED, ResourceLaneEvent.Type.CIRCUIT_OPENED,
					ResourceLaneEvent.Type.CIRCUIT_HALF_OPEN, ResourceLaneEvent.Type.CIRCUIT_CLOSED);
		}
		ExecutionResourceKey wormsLane = ExecutionResourceKey.source("worms");
		assertThat(reported.get()).isSameAs(service.lastRunStatistics());
		ExecutionRunStatistics.Entry lane = service.lastRunStatistics().resource(wormsLane).orElseThrow();
		assertThat(lane.circuitOpenings()).isEqualTo(1);
		assertThat(lane.externalFailures()).isEqualTo(3);
		assertThat(lane.maxConcurrency()).isLessThanOrEqualTo(2);
		assertThat(lane.invocations()).as("six groups in each of PRE and POST").isEqualTo(12);
		assertThat(service.lastRunStatistics().test("local").orElseThrow().invocations()).isEqualTo(12);
	}

	@Test
	void cancellationDuringACooldownStopsPromptlyWithoutInvokingPausedWork() throws Exception {
		AtomicInteger wormsCalls = new AtomicInteger();
		CountDownLatch opened = new CountDownLatch(1);
		ExecutionAdapter adapter = (record, binding, implementation) -> {
			wormsCalls.incrementAndGet();
			return ExecutionTestSupport.externalFailure(record, binding, "HTTP 503");
		};
		ExecutionPolicy policy = ExecutionPolicy.builder()
				.prePostReuseEnabled(false)
				.retriesEnabled(false)
				.externalConcurrency(1)
				.circuitFailureThreshold(1)
				.circuitInitialCooldown(Duration.ofHours(1))
				.circuitMaxCooldown(Duration.ofHours(1))
				.build();
		ExecutionProgressListener listener = new ExecutionProgressListener() {
			@Override
			public void onResourceLaneEvent(Phase phase, ResourceLaneEvent event) {
				if (event.type() == ResourceLaneEvent.Type.CIRCUIT_OPENED) {
					opened.countDown();
				}
			}
		};
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, adapter, listener, true, policy);
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		Thread runner = new Thread(() -> {
			try {
				service.execute(ExecutionTestSupport.distinctDataset("f", 5), List.of(WORMS), List.of());
			} catch (Throwable t) {
				thrown.set(t);
			}
		});
		runner.start();
		assertThat(opened.await(5, TimeUnit.SECONDS)).isTrue();

		runner.interrupt();
		runner.join(TimeUnit.SECONDS.toMillis(5));

		assertThat(runner.isAlive()).isFalse();
		assertThat(thrown.get()).isInstanceOf(CancellationException.class);
		assertThat(wormsCalls.get()).as("paused work was never invoked").isEqualTo(1);
		assertThat(service.lastRunStatistics().resource(ExecutionResourceKey.source("worms")).orElseThrow()
				.circuitOpenings()).isEqualTo(1);
	}
}
