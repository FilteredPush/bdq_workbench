/** PrePostResultReuse.java
 *
 * Run-scoped store of successful PRE_AMENDMENT group results that POST_AMENDMENT may reuse when
 * an invocation's fingerprint is unchanged by the AMENDMENT phase.
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

import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

/**
 * Remembers the successful PRE_AMENDMENT result of each distinct {@link InvocationFingerprint}
 * and hands out POST_AMENDMENT copies of it.
 *
 * <p>Reuse is limited to POST_AMENDMENT bindings that were <em>rebound</em> from PRE_AMENDMENT
 * bindings (see {@link #markRebound}); a test with an explicit POST_AMENDMENT binding is always
 * invoked, since its policy asks for a distinct invocation. Only results the
 * {@link ResponseFailureClassifier} considers {@link FailureCategory#COMPLETED ordinary completed
 * results} are remembered — never errors, unable-to-run or not-implemented results, external
 * prerequisite failures (transient or not), or derived rollups — so a failed PRE call is always
 * attempted again in POST.
 *
 * <p>One instance lives for one execution run and is safe for concurrent use.
 */
final class PrePostResultReuse {

	/** Note appended to the message of a reused response. */
	static final String REUSE_NOTE = "[workbench: reused the PRE_AMENDMENT result; inputs unchanged by amendments]";

	private final boolean enabled;
	private final Map<InvocationFingerprint, Response> results = new ConcurrentHashMap<>();
	private final Set<ImplementationBinding> rebound = Collections.synchronizedSet(
			Collections.newSetFromMap(new IdentityHashMap<>()));

	/**
	 * Creates a store.
	 *
	 * @param enabled whether reuse is enabled at all; when {@code false} nothing is remembered
	 *     and nothing is reused
	 */
	PrePostResultReuse(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Returns whether reuse is enabled.
	 *
	 * @return whether reuse is enabled
	 */
	boolean enabled() {
		return enabled;
	}

	/**
	 * Marks a POST_AMENDMENT binding as a copy of a PRE_AMENDMENT binding, making it eligible for
	 * reuse. Bindings are tracked by identity.
	 *
	 * @param binding the rebound POST_AMENDMENT binding
	 */
	void markRebound(ImplementationBinding binding) {
		if (enabled) {
			rebound.add(binding);
		}
	}

	/**
	 * Returns whether {@code binding} was marked as rebound from PRE_AMENDMENT.
	 *
	 * @param binding the binding
	 * @return whether it may reuse PRE_AMENDMENT results
	 */
	boolean isRebound(ImplementationBinding binding) {
		return enabled && rebound.contains(binding);
	}

	/**
	 * Remembers a PRE_AMENDMENT group result if it is an ordinary completed result.
	 *
	 * @param fingerprint the invocation's fingerprint
	 * @param response the group's representative response
	 * @return whether the response was remembered
	 */
	boolean remember(InvocationFingerprint fingerprint, Response response) {
		if (!enabled || fingerprint == null || !isReusable(response)) {
			return false;
		}
		results.put(fingerprint, response);
		return true;
	}

	/**
	 * Looks up a remembered result and copies it into the POST_AMENDMENT phase.
	 *
	 * @param fingerprint the POST_AMENDMENT invocation's fingerprint
	 * @param postBinding the POST_AMENDMENT binding the copy is for
	 * @return the POST_AMENDMENT copy, or empty if there is no remembered result
	 */
	Optional<Response> reuse(InvocationFingerprint fingerprint, ImplementationBinding postBinding) {
		if (!enabled || fingerprint == null) {
			return Optional.empty();
		}
		Response pre = results.get(fingerprint);
		return pre == null ? Optional.empty() : Optional.of(copyForPhase(pre, postBinding.phase()));
	}

	/**
	 * Returns whether a response may be reused: an ordinary completed, non-derived result.
	 *
	 * @param response the response
	 * @return whether it may stand in for a later identical invocation
	 */
	static boolean isReusable(Response response) {
		return response != null
				&& !response.derived()
				&& response.status() != OutcomeStatus.ERROR
				&& ResponseFailureClassifier.classify(response) == FailureCategory.COMPLETED;
	}

	/**
	 * Copies a response into another phase, with fresh timestamps and a reuse note on its
	 * message; status, result, comment, and amendments are unchanged.
	 *
	 * @param response the PRE_AMENDMENT response
	 * @param phase the phase of the copy
	 * @return the copy
	 */
	private static Response copyForPhase(Response response, Phase phase) {
		Instant now = Instant.now();
		String message = response.message() == null || response.message().isBlank()
				? REUSE_NOTE
				: response.message() + " " + REUSE_NOTE;
		return new Response(
				response.recordId(),
				response.testId(),
				response.testType(),
				response.implementationClass(),
				response.implementationMethod(),
				phase,
				response.parameters(),
				response.status(),
				response.responseStatus(),
				response.responseResult(),
				response.comment(),
				message,
				response.amendments(),
				now,
				now,
				response.subjectRef(),
				response.derived(),
				response.contributingSubjectRefs());
	}
}
