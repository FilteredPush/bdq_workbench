/** ResponseFailureClassifier.java
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

import java.util.Locale;
import java.util.regex.Pattern;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Response;

/**
 * Decides what a completed invocation's {@link Response} says about the resource it used. This is
 * the one place that interprets upstream diagnostics, since implementation libraries vary widely
 * in how much they say about a failure.
 *
 * <p>Structured fields are consulted first: a {@code responseStatus} of
 * {@code EXTERNAL_PREREQUISITES_NOT_MET} is an external failure; {@code ERROR},
 * {@code UNABLE_TO_RUN} and {@code NOT_IMPLEMENTED} outcomes are {@link FailureCategory#INTERNAL};
 * anything else is {@link FailureCategory#COMPLETED}. Only then are the comment and message
 * inspected (case-insensitively) to refine an external failure: configuration indicators (invalid
 * or unsupported source authority, HTTP 400/401/403/404, forbidden, unauthorized) make it
 * {@link FailureCategory#NON_TRANSIENT_CONFIGURATION} and win over transient indicators; transient
 * indicators (timeouts, connection and DNS failures, rate limiting, HTTP 429 and 5xx) make it
 * {@link FailureCategory#TRANSIENT_EXTERNAL}; with neither it is
 * {@link FailureCategory#AMBIGUOUS_EXTERNAL}. An {@code ERROR} whose text carries a transient
 * network indicator (an uncaught {@code SocketTimeoutException}, for example) is also treated as
 * {@link FailureCategory#TRANSIENT_EXTERNAL}.
 */
public final class ResponseFailureClassifier {

	/** The response status BDQ uses for a failed external prerequisite. */
	public static final String EXTERNAL_PREREQUISITES_NOT_MET = "EXTERNAL_PREREQUISITES_NOT_MET";

	private static final Pattern NON_TRANSIENT = Pattern.compile(
			"(?:invalid|unsupported|unknown|unrecognized|unrecognised|not\\s+supported)\\s+(?:specified\\s+)?(?:source\\s*)?authority"
					+ "|source\\s*authority[^.;]{0,60}?\\b(?:is\\s+)?(?:not\\s+supported|not\\s+recogni[sz]ed|invalid|unsupported)"
					+ "|\\b(?:http|status|code|response)\\W{0,3}(?:400|401|403|404)\\b"
					+ "|\\bforbidden\\b|\\bunauthori[sz]ed\\b|\\bbad\\s+request\\b");

	private static final Pattern TRANSIENT = Pattern.compile(
			"time[ds]?\\s*out|timeout"
					+ "|connect(?:ion)?\\s*(?:exception|refused|reset|closed|aborted|failed|error)"
					+ "|unknown\\s*host|unknownhostexception|no\\s+route\\s+to\\s+host|name\\s+resolution|could\\s+not\\s+resolve|dns"
					+ "|temporar(?:il)?y\\s+unavailable|service\\s+unavailable|bad\\s+gateway|gateway\\s+time"
					+ "|too\\s+many\\s+requests|rate[\\s-]*limit|throttl"
					+ "|\\b(?:http|status|code|response)\\W{0,3}(?:429|5\\d\\d)\\b"
					+ "|\\b(?:429|502|503|504)\\b");

	/**
	 * Not instantiable.
	 */
	private ResponseFailureClassifier() {
	}

	/**
	 * Classifies a response.
	 *
	 * @param response the response, or {@code null} if the invocation produced none
	 * @return the failure category
	 */
	public static FailureCategory classify(Response response) {
		if (response == null) {
			return FailureCategory.INTERNAL;
		}
		String responseStatus = normalizeStatus(response.responseStatus());
		if (EXTERNAL_PREREQUISITES_NOT_MET.equals(responseStatus)) {
			return classifyExternal(diagnosticText(response));
		}
		OutcomeStatus status = response.status();
		if (status == OutcomeStatus.ERROR || "ERROR".equals(responseStatus)) {
			return TRANSIENT.matcher(diagnosticText(response)).find()
					&& !NON_TRANSIENT.matcher(diagnosticText(response)).find()
							? FailureCategory.TRANSIENT_EXTERNAL
							: FailureCategory.INTERNAL;
		}
		if (status == OutcomeStatus.UNABLE_TO_RUN || status == OutcomeStatus.NOT_IMPLEMENTED
				|| "UNABLE_TO_RUN".equals(responseStatus) || "NOT_IMPLEMENTED".equals(responseStatus)) {
			return FailureCategory.INTERNAL;
		}
		return FailureCategory.COMPLETED;
	}

	/**
	 * Classifies the text of an external-prerequisite failure.
	 *
	 * @param text the lower-cased diagnostic text
	 * @return the external failure category
	 */
	static FailureCategory classifyExternal(String text) {
		if (NON_TRANSIENT.matcher(text).find()) {
			return FailureCategory.NON_TRANSIENT_CONFIGURATION;
		}
		if (TRANSIENT.matcher(text).find()) {
			return FailureCategory.TRANSIENT_EXTERNAL;
		}
		return FailureCategory.AMBIGUOUS_EXTERNAL;
	}

	/**
	 * Returns whether a response reports a failed external prerequisite, whatever its cause.
	 *
	 * @param response the response
	 * @return whether its response status is {@code EXTERNAL_PREREQUISITES_NOT_MET}
	 */
	public static boolean isExternalPrerequisiteFailure(Response response) {
		return response != null && EXTERNAL_PREREQUISITES_NOT_MET.equals(normalizeStatus(response.responseStatus()));
	}

	/**
	 * Normalizes a response status for comparison: trimmed, upper-cased, with a namespace prefix
	 * such as {@code bdq:} removed.
	 *
	 * @param responseStatus the raw response status
	 * @return the normalized status, or an empty string
	 */
	private static String normalizeStatus(String responseStatus) {
		if (responseStatus == null) {
			return "";
		}
		String trimmed = responseStatus.trim();
		int colon = trimmed.lastIndexOf(':');
		int slash = trimmed.lastIndexOf('/');
		int cut = Math.max(colon, slash);
		return (cut >= 0 ? trimmed.substring(cut + 1) : trimmed).toUpperCase(Locale.ROOT);
	}

	/**
	 * Joins a response's comment and message into one lower-cased string to search.
	 *
	 * @param response the response
	 * @return the diagnostic text, possibly empty
	 */
	private static String diagnosticText(Response response) {
		String comment = response.comment() == null ? "" : response.comment();
		String message = response.message() == null || response.message().equals(comment) ? "" : response.message();
		return (comment + " " + message).toLowerCase(Locale.ROOT);
	}
}
