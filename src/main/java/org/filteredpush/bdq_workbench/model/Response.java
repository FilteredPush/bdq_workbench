/** Response.java
 *
 * Result for a single test execution against a single record (or the synthetic "MULTIRECORD" pseudo-record), with full provenance details.
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
package org.filteredpush.bdq_workbench.model;

import java.time.Instant;
import java.util.Map;

/**
 * Result for a single test execution with provenance details.
 *
 * @param recordId the identifier of the record the test was run against, {@code "*"} for
 *     synthesized unresolved/unbound placeholders, or {@code "MULTIRECORD"} for built-in
 *     multi-record measures
 * @param testId the identifier of the test that was executed
 * @param testType the high-level category of the test that was executed
 * @param implementationClass the fully-qualified class name of the implementation that was
 *     invoked
 * @param implementationMethod the name of the implementation method that was invoked
 * @param phase the execution phase the test belongs to
 * @param parameters the parameter values supplied to the implementation method
 * @param status the high-level outcome classification of this execution
 * @param responseStatus the BDQ response status string (e.g. {@code "RUN_HAS_RESULT"})
 * @param responseResult the BDQ response result string (e.g. {@code "COMPLIANT"},
 *     {@code "FILLED_IN"})
 * @param comment a short human-readable comment on the outcome
 * @param message a detailed human-readable message about the outcome
 * @param amendments term name to new value pairs produced by an AMENDMENT-phase test, empty for
 *     other test types
 * @param startedAt the instant execution of this test began
 * @param finishedAt the instant execution of this test completed
 */
public record Response(
        String recordId,
        String testId,
        TestType testType,
        String implementationClass,
        String implementationMethod,
        Phase phase,
        Map<String, String> parameters,
        OutcomeStatus status,
        String responseStatus,
        String responseResult,
        String comment,
        String message,
        Map<String, String> amendments,
        Instant startedAt,
        Instant finishedAt) {
}
