/** RetrySchedulerTest.java
 *
 * Tests workbench-level retries: which failures are retried, backoff delays, lane rules for
 * retried work, final response annotation, cancellation of pending retries, and amendment
 * sequencing with retries, all without network access.
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class RetrySchedulerTest {

	private static final CanonicalRecord RECORD = new CanonicalRecord("r1", Map.of());
	private static final ImplementationBinding BINDING = ExecutionTestSupport.binding("remote-test", TestType.VALIDATION,
			"org.example.Remote", "validate", Phase.PRE_AMENDMENT, "f");
	private static final ExecutionResourceKey LANE = ExecutionResourceKey.source("remote");
	private static final ResourceAssignment ASSIGNMENT = new ResourceAssignment(LANE, ResourceClass.EXTERNAL, "test", 1, false);
	private static final ExecutionPolicy POLICY = ExecutionPolicy.builder().circuitFailureThreshold(0).build();

	private final ManualDelayScheduler timer = new ManualDelayScheduler();
	private final List<ResourceLaneEvent> events = Collections.synchronizedList(new ArrayList<>());
	private final ExecutionStatisticsCollector statistics = new ExecutionStatisticsCollector();

	private ResourceLaneScheduler scheduler(ExecutionPolicy policy) {
		return new ResourceLaneScheduler(2, policy, timer, (phase, event) -> events.add(event), statistics,
				new RetryPolicy(policy, () -> 0.0d));
	}

	private static CompletableFuture<Response> submit(ResourceLaneScheduler scheduler, java.util.concurrent.Callable<Response> task) {
		CompletableFuture<Response> result = new CompletableFuture<>();
		scheduler.submit(Phase.PRE_AMENDMENT, ASSIGNMENT, BINDING, task, result);
		return result;
	}

	private static void awaitPending(ManualDelayScheduler timer, int count) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (timer.pendingCount() < count && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		assertThat(timer.pendingCount()).isEqualTo(count);
	}

	private List<ResourceLaneEvent.Type> eventTypes() {
		synchronized (events) {
			return events.stream().map(ResourceLaneEvent::type).toList();
		}
	}

	@Test
	void retryPolicyAllowsOnlyTransientAndAmbiguousFailuresWithBoundedExponentialBackoff() {
		RetryPolicy noJitter = new RetryPolicy(ExecutionPolicy.defaults(), () -> 0.0d);
		assertThat(noJitter.maxRetries(FailureCategory.TRANSIENT_EXTERNAL)).isEqualTo(2);
		assertThat(noJitter.maxRetries(FailureCategory.AMBIGUOUS_EXTERNAL)).isEqualTo(1);
		assertThat(noJitter.maxRetries(FailureCategory.NON_TRANSIENT_CONFIGURATION)).isZero();
		assertThat(noJitter.maxRetries(FailureCategory.INTERNAL)).isZero();
		assertThat(noJitter.maxRetries(FailureCategory.COMPLETED)).isZero();
		assertThat(List.of(1, 2, 3, 4, 5, 6, 60).stream().map(noJitter::delayBeforeRetry).map(Duration::toMillis))
				.containsExactly(500L, 1000L, 2000L, 4000L, 8000L, 8000L, 8000L);

		RetryPolicy fullJitter = new RetryPolicy(ExecutionPolicy.defaults(), () -> 1.0d);
		assertThat(fullJitter.delayBeforeRetry(1)).isEqualTo(Duration.ofMillis(250));

		RetryPolicy disabled = new RetryPolicy(ExecutionPolicy.builder().retriesEnabled(false).build());
		assertThat(disabled.maxRetries(FailureCategory.TRANSIENT_EXTERNAL)).isZero();
	}

	@Test
	void transientFailureIsRetriedAfterABackoffAndTheRecoveredResultIsAnnotated() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ResourceLaneScheduler scheduler = scheduler(POLICY)) {
			CompletableFuture<Response> result = submit(scheduler, () -> calls.incrementAndGet() == 1
					? ExecutionTestSupport.externalFailure(RECORD, BINDING, "HTTP 503 Service Unavailable")
					: ExecutionTestSupport.compliant(RECORD, BINDING));
			awaitPending(timer, 1);
			assertThat(result).isNotDone();
			assertThat(timer.requestedDelays()).containsExactly(Duration.ofMillis(500));

			timer.runPending();
			Response response = result.get(5, TimeUnit.SECONDS);

			assertThat(calls.get()).isEqualTo(2);
			assertThat(response.responseResult()).isEqualTo("COMPLIANT");
			assertThat(response.comment()).as("the implementation's comment is untouched").isEqualTo("ok");
			assertThat(response.message()).contains("attempt 2", "1 external failure");
		}
		assertThat(eventTypes()).containsSubsequence(ResourceLaneEvent.Type.RETRY_SCHEDULED, ResourceLaneEvent.Type.RETRY_SUCCEEDED);
		ExecutionRunStatistics.Entry entry = statistics.snapshot().test("remote-test").orElseThrow();
		assertThat(entry.retries()).isEqualTo(1);
		assertThat(entry.recoveredRetries()).isEqualTo(1);
		assertThat(entry.invocations()).isEqualTo(2);
	}

	@Test
	void configurationFailuresAreNeverRetried() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ResourceLaneScheduler scheduler = scheduler(POLICY)) {
			Response response = submit(scheduler, () -> {
				calls.incrementAndGet();
				return ExecutionTestSupport.externalFailure(RECORD, BINDING, "Invalid source authority: nowhere");
			}).get(5, TimeUnit.SECONDS);

			assertThat(calls.get()).isEqualTo(1);
			assertThat(response.message()).isEqualTo("Invalid source authority: nowhere");
			assertThat(timer.requestedDelays()).isEmpty();
		}
	}

	@Test
	void exhaustedRetriesKeepTheLatestExternalPrerequisiteDiagnostic() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ResourceLaneScheduler scheduler = scheduler(POLICY)) {
			CompletableFuture<Response> result = submit(scheduler, () -> ExecutionTestSupport.externalFailure(RECORD, BINDING,
					"attempt " + calls.incrementAndGet() + " timed out"));
			for (int retry = 1; retry <= 2; retry++) {
				awaitPending(timer, 1);
				timer.runPending();
			}
			Response response = result.get(5, TimeUnit.SECONDS);

			assertThat(calls.get()).isEqualTo(3);
			assertThat(timer.requestedDelays()).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
			assertThat(response.status()).isEqualTo(OutcomeStatus.FAILED);
			assertThat(response.responseStatus()).isEqualTo("EXTERNAL_PREREQUISITES_NOT_MET");
			assertThat(response.comment()).isEqualTo("attempt 3 timed out");
			assertThat(response.message()).contains("still not met after 3 attempts");
		}
		assertThat(eventTypes()).contains(ResourceLaneEvent.Type.RETRY_EXHAUSTED);
		assertThat(statistics.snapshot().resource(LANE).orElseThrow().exhaustedRetries()).isEqualTo(1);
	}

	@Test
	void ambiguousFailuresAreRetriedOnlyOnce() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ResourceLaneScheduler scheduler = scheduler(POLICY)) {
			CompletableFuture<Response> result = submit(scheduler, () -> {
				calls.incrementAndGet();
				return ExecutionTestSupport.externalFailure(RECORD, BINDING, "Error accessing Source Authority: ");
			});
			awaitPending(timer, 1);
			timer.runPending();
			result.get(5, TimeUnit.SECONDS);

			assertThat(calls.get()).isEqualTo(2);
		}
	}

	@Test
	void retriedWorkWaitsForItsLanePermit() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch release = new CountDownLatch(1);
		try (ResourceLaneScheduler scheduler = scheduler(POLICY)) {
			CompletableFuture<Response> retried = submit(scheduler, () -> calls.incrementAndGet() == 1
					? ExecutionTestSupport.externalFailure(RECORD, BINDING, "Connection reset")
					: ExecutionTestSupport.compliant(RECORD, BINDING));
			awaitPending(timer, 1);
			CompletableFuture<Response> blocker = submit(scheduler, () -> {
				release.await(5, TimeUnit.SECONDS);
				return ExecutionTestSupport.compliant(RECORD, BINDING);
			});
			Thread.sleep(20);

			timer.runPending();
			Thread.sleep(50);
			assertThat(calls.get()).as("the lane's single permit is held by the blocker").isEqualTo(1);

			release.countDown();
			blocker.get(5, TimeUnit.SECONDS);
			assertThat(retried.get(5, TimeUnit.SECONDS).responseResult()).isEqualTo("COMPLIANT");
			assertThat(calls.get()).isEqualTo(2);
		}
	}

	@Test
	void closingCancelsPendingRetries() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		ResourceLaneScheduler scheduler = scheduler(POLICY);
		CompletableFuture<Response> result = submit(scheduler, () -> {
			calls.incrementAndGet();
			return ExecutionTestSupport.externalFailure(RECORD, BINDING, "timed out");
		});
		awaitPending(timer, 1);

		scheduler.close();

		assertThat(result.isCancelled()).isTrue();
		assertThat(timer.runPending()).isZero();
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void amendmentsAreAppliedOnlyFromTheFinalResponseAfterRetriesBeforeTheNextAmendmentBinding() {
		Map<String, AtomicInteger> amendCalls = new ConcurrentHashMap<>();
		Map<String, String> seenByNext = new ConcurrentHashMap<>();
		ImplementationBinding amend = ExecutionTestSupport.withParameter(ExecutionTestSupport.binding("amend",
				TestType.AMENDMENT, "org.example.Remote", "amendmentCountrycodeStandardized", Phase.AMENDMENT, "countryCode"),
				"bdq:sourceAuthority", "remote");
		ImplementationBinding next = ExecutionTestSupport.binding("next", TestType.AMENDMENT, "org.example.Local",
				"amendmentNoteNotempty", Phase.AMENDMENT, "countryCode");
		ExecutionAdapter flaky = (record, binding, implementation) -> {
			String value = record.terms().get("countryCode");
			if (!binding.testId().equals("amend")) {
				seenByNext.put(record.id(), value);
				return ExecutionTestSupport.response(record, binding, OutcomeStatus.PASSED, "NOT_AMENDED", null, "seen");
			}
			if (amendCalls.computeIfAbsent(value, key -> new AtomicInteger()).incrementAndGet() == 1) {
				return ExecutionTestSupport.externalFailure(record, binding, "HTTP 503");
			}
			return ExecutionTestSupport.response(record, binding, OutcomeStatus.AMENDED, "AMENDED", null,
					"standardized", Map.of("dwc:countryCode", value.toUpperCase()));
		};
		ExecutionPolicy policy = ExecutionPolicy.builder()
				.prePostReuseEnabled(false)
				.retryBaseDelay(Duration.ofMillis(1))
				.retryMaxDelay(Duration.ofMillis(5))
				.circuitInitialCooldown(Duration.ofMillis(5))
				.circuitMaxCooldown(Duration.ofMillis(20))
				.build();
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(3, flaky, null, true, policy);

		List<Response> responses = service.execute(ExecutionTestSupport.distinctDataset("countryCode", 4),
				List.of(amend, next), List.of());

		List<Response> amendments = responses.stream()
				.filter(r -> r.phase() == Phase.AMENDMENT && r.testId().equals("amend")).toList();
		assertThat(amendments).hasSize(4).allSatisfy(r -> {
			assertThat(r.status()).isEqualTo(OutcomeStatus.AMENDED);
			assertThat(r.comment()).as("the transient failure was retried, not reported").isEqualTo("standardized");
			assertThat(r.message()).contains("attempt 2");
		});
		assertThat(seenByNext).as("the next amendment binding saw the final amended values")
				.containsOnly(Map.entry("r0", "V0"), Map.entry("r1", "V1"), Map.entry("r2", "V2"), Map.entry("r3", "V3"));
		assertThat(service.lastRunStatistics().test("amend").orElseThrow().recoveredRetries()).isEqualTo(4);
	}
}
