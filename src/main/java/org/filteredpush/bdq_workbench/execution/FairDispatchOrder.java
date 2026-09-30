/** FairDispatchOrder.java
 *
 * Interleaves the distinct-value group invocations of several bindings round-robin, so that no
 * single binding monopolizes the worker pool when a phase starts.
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Computes a fair dispatch order for the group invocations of several bindings.
 *
 * <p>Submitting every group of binding A before any group of binding B fills every worker with
 * concurrent invocations of A. If A calls an external service, that creates a burst of
 * simultaneous requests against the service. Interleaving the bindings round-robin — group 1 of A,
 * group 1 of B, group 1 of C, group 2 of A, … — spreads the first wave of work across bindings
 * instead. The order only affects when work is dispatched; responses are still collected per
 * binding and sorted deterministically afterwards, so externally visible ordering is unchanged.
 */
final class FairDispatchOrder {

	/**
	 * Not instantiable; this class only offers a static helper.
	 */
	private FairDispatchOrder() {
	}

	/**
	 * Interleaves the items of several ordered lists round-robin, preserving the relative order of
	 * each list's own items.
	 *
	 * @param <P> the type of the containers (for example per-binding execution plans)
	 * @param <T> the type of the items to interleave
	 * @param containers the containers, in the order their items should be visited within a round
	 * @param items extracts one container's ordered items
	 * @return every item of every container, interleaved round-robin
	 */
	static <P, T> List<T> roundRobin(List<P> containers, Function<P, List<T>> items) {
		List<List<T>> lists = containers.stream().map(items).toList();
		int rounds = lists.stream().mapToInt(List::size).max().orElse(0);
		List<T> order = new ArrayList<>(lists.stream().mapToInt(List::size).sum());
		for (int round = 0; round < rounds; round++) {
			for (List<T> list : lists) {
				if (round < list.size()) {
					order.add(list.get(round));
				}
			}
		}
		return order;
	}
}
