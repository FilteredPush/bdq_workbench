/** ResourceAssignment.java
 *
 * The resource lane a binding's invocations are assigned to, with the classification, the
 * explanation of how it was reached, and the lane's configured concurrency.
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

/**
 * The outcome of classifying one binding (see {@link ExecutionResourceClassifier}).
 *
 * @param key the resource lane the binding's invocations run in
 * @param resourceClass the binding's (hinted or configured) resource class
 * @param reason a human-readable explanation of how the key and class were derived, for logs
 *     and diagnostics
 * @param maxConcurrency the lane's configured maximum number of concurrent invocations, at least 1
 * @param pinned whether the classification or limit comes from explicit configuration, in which
 *     case runtime learning may still lower the limit on failures but never raises it above
 *     {@code maxConcurrency} or reclassifies the lane
 */
public record ResourceAssignment(
		ExecutionResourceKey key,
		ResourceClass resourceClass,
		String reason,
		int maxConcurrency,
		boolean pinned) {

	/**
	 * Clamps {@code maxConcurrency} to at least 1.
	 */
	public ResourceAssignment {
		maxConcurrency = Math.max(1, maxConcurrency);
	}
}
