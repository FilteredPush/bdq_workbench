/** FairDispatchTest.java
 *
 * Tests that PRE_AMENDMENT/POST_AMENDMENT group invocations are dispatched round-robin across
 * bindings rather than binding by binding.
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class FairDispatchTest {

	@Test
	void roundRobinInterleavesListsPreservingEachListsOrder() {
		List<List<String>> lists = List.of(List.of("a1", "a2", "a3"), List.of("b1"), List.of("c1", "c2"));

		assertThat(FairDispatchOrder.roundRobin(lists, list -> list))
				.containsExactly("a1", "b1", "c1", "a2", "c2", "a3");
	}

	@Test
	void initialWorkersAreSharedAmongBindingsInsteadOfMonopolizedByTheFirst() {
		int threads = 3;
		List<String> startOrder = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch firstWave = new CountDownLatch(threads);
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(threads, (record, binding, implementation) -> {
			startOrder.add(binding.testId());
			firstWave.countDown();
			try {
				firstWave.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return ExecutionTestSupport.compliant(record, binding);
		});
		List<ImplementationBinding> bindings = List.of(
				ExecutionTestSupport.binding("A", TestType.VALIDATION, "org.example.Local", "validationANotempty", Phase.PRE_AMENDMENT, "f"),
				ExecutionTestSupport.binding("B", TestType.VALIDATION, "org.example.Local", "validationBNotempty", Phase.PRE_AMENDMENT, "f"),
				ExecutionTestSupport.binding("C", TestType.VALIDATION, "org.example.Local", "validationCNotempty", Phase.PRE_AMENDMENT, "f"));
		RecordDataset dataset = ExecutionTestSupport.distinctDataset("f", 4);

		List<Response> responses = service.execute(dataset, bindings, List.of());

		assertThat(startOrder.subList(0, threads))
				.as("the first wave of concurrent invocations covers every binding")
				.containsExactlyInAnyOrder("A", "B", "C");
		assertThat(responses.stream().filter(r -> r.phase() == Phase.PRE_AMENDMENT).map(Response::testId).toList())
				.as("result ordering is still deterministic: by test, then record")
				.containsExactly("A", "A", "A", "A", "B", "B", "B", "B", "C", "C", "C", "C");
	}
}
