/** FailureCategory.java
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
 * What an invocation's {@link org.filteredpush.bdq_workbench.model.Response} says about the health
 * of the resource it used, as decided by {@link ResponseFailureClassifier}.
 */
public enum FailureCategory {
	/**
	 * The test ran to an ordinary conclusion (a result, an amendment, or an internal-prerequisites
	 * outcome about the data itself); the resource, if any, answered.
	 */
	COMPLETED,
	/**
	 * An external prerequisite failed in a way that indicates a transient service problem (timeout,
	 * connection failure, DNS failure, rate limiting, HTTP 429 or 5xx): worth throttling for and
	 * retrying.
	 */
	TRANSIENT_EXTERNAL,
	/**
	 * An external prerequisite failed without a diagnostic that says why: treated conservatively,
	 * throttled for and retried a limited number of times.
	 */
	AMBIGUOUS_EXTERNAL,
	/**
	 * An external prerequisite failed because of how the test was configured (an invalid or
	 * unsupported source authority, HTTP 400/401/403/404): retrying cannot help, and the failure
	 * says nothing about the service's health.
	 */
	NON_TRANSIENT_CONFIGURATION,
	/**
	 * The invocation failed inside the workbench or the implementation (an error, an unable-to-run
	 * or not-implemented outcome, or a thrown exception): not a resource health signal and never
	 * retried.
	 */
	INTERNAL;

	/**
	 * Returns whether this category signals a possibly unhealthy external resource.
	 *
	 * @return {@code true} for {@link #TRANSIENT_EXTERNAL} and {@link #AMBIGUOUS_EXTERNAL}
	 */
	public boolean isExternalHealthFailure() {
		return this == TRANSIENT_EXTERNAL || this == AMBIGUOUS_EXTERNAL;
	}
}
