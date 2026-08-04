/** ExecutionSummary.java
 *
 * Summary of a workbench run's execution outputs, including unresolved outcomes, with query helpers used by reporting.
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

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Summary of execution outputs including unresolved outcomes.
 *
 * <p>Wraps the flat list of {@link Response}s produced by a run (both executed and synthesized
 * {@code UNABLE_TO_RUN} placeholders) together with aggregated {@link ExecutionSummaryMetadata},
 * and exposes filtering and counting helpers used by
 * {@link org.filteredpush.bdq_workbench.reporting.ReportingService} implementations to render
 * reports.
 *
 * @param responses all responses for the run, executed and synthesized
 * @param metadata aggregated metadata about the run (use case identity, dataset info, and
 *     value-change summaries)
 * @param dataset the input dataset the run was executed against, used by
 *     {@link org.filteredpush.bdq_workbench.reporting.RdfResponseExporter} to enrich each
 *     response's record resource with its term values; empty when not supplied (e.g. by the two
 *     convenience constructors)
 */
public record ExecutionSummary(List<Response> responses, ExecutionSummaryMetadata metadata, RecordDataset dataset) {

    /**
     * Canonical constructor; defensively copies {@code responses}, substitutes
     * {@link ExecutionSummaryMetadata#empty()} for a null {@code metadata}, and substitutes an
     * empty dataset for a null {@code dataset}.
     */
    public ExecutionSummary {
        responses = List.copyOf(responses);
        metadata = metadata == null ? ExecutionSummaryMetadata.empty() : metadata;
        dataset = dataset == null ? new RecordDataset(List.of()) : dataset;
    }

    /**
     * Creates a summary with the given metadata and an empty dataset.
     *
     * @param responses all responses for the run, executed and synthesized
     * @param metadata aggregated metadata about the run
     */
    public ExecutionSummary(List<Response> responses, ExecutionSummaryMetadata metadata) {
        this(responses, metadata, new RecordDataset(List.of()));
    }

    /**
     * Creates a summary with empty metadata and an empty dataset.
     *
     * @param responses all responses for the run, executed and synthesized
     */
    public ExecutionSummary(List<Response> responses) {
        this(responses, ExecutionSummaryMetadata.empty(), new RecordDataset(List.of()));
    }

    /**
     * Returns the responses belonging to a given execution phase.
     *
     * @param phase the phase to filter by
     * @return responses whose {@link Response#phase()} equals {@code phase}
     */
    public List<Response> responsesForPhase(Phase phase) {
        return filter(response -> response.phase() == phase);
    }

    /**
     * Returns the responses belonging to a given test type.
     *
     * @param type the test type to filter by
     * @return responses whose {@link Response#testType()} equals {@code type}
     */
    public List<Response> responsesForType(TestType type) {
        return filter(response -> response.testType() == type);
    }

    /**
     * Returns the responses with a given response status.
     *
     * @param responseStatus the response status to filter by
     * @return responses whose {@link Response#responseStatus()} equals {@code responseStatus}
     */
    public List<Response> responsesForResponseStatus(String responseStatus) {
        return filter(response -> responseStatus.equals(response.responseStatus()));
    }

    /**
     * Counts responses matching both a phase and a response status.
     *
     * @param phase the phase to match
     * @param responseStatus the response status to match
     * @return the number of responses matching both criteria
     */
    public long countByPhaseAndStatus(Phase phase, String responseStatus) {
        return responses.stream()
                .filter(response -> response.phase() == phase)
                .filter(response -> responseStatus.equals(response.responseStatus()))
                .count();
    }

    /**
     * Counts responses matching both a test type and a response result.
     *
     * @param type the test type to match
     * @param responseResult the response result to match
     * @return the number of responses matching both criteria
     */
    public long countByTypeAndResult(TestType type, String responseResult) {
        return responses.stream()
                .filter(response -> response.testType() == type)
                .filter(response -> responseResult.equals(response.responseResult()))
                .count();
    }

    /**
     * Tallies responses by response status.
     *
     * @return counts keyed by {@link Response#responseStatus()}, with null/blank statuses
     *     grouped under {@code "<none>"}
     */
    public Map<String, Long> countsByResponseStatus() {
        return responses.stream()
                .collect(Collectors.groupingBy(response -> defaulted(response.responseStatus()), Collectors.counting()));
    }

    /**
     * Tallies responses by response result.
     *
     * @return counts keyed by {@link Response#responseResult()}, with null/blank results
     *     grouped under {@code "<none>"}
     */
    public Map<String, Long> countsByResponseResult() {
        return responses.stream()
                .collect(Collectors.groupingBy(response -> defaulted(response.responseResult()), Collectors.counting()));
    }

    /**
     * Tallies responses by execution phase.
     *
     * @return counts keyed by {@link Response#phase()}
     */
    public Map<Phase, Long> countsByPhase() {
        return responses.stream()
                .collect(Collectors.groupingBy(Response::phase, Collectors.counting()));
    }

    /**
     * Returns the synthesized multi-record MEASURE responses (those with the special
     * {@code "MULTIRECORD"} record ID) produced by built-in measures.
     *
     * @return MEASURE-type responses whose {@link Response#recordId()} is {@code "MULTIRECORD"}
     */
    public List<Response> multiRecordMeasureResponses() {
        return responses.stream()
                .filter(response -> response.testType() == TestType.MEASURE)
                .filter(response -> "MULTIRECORD".equals(response.recordId()))
                .toList();
    }

    /**
     * Groups the multi-record measure responses by test ID and then by phase, keeping the last
     * response encountered for a given test/phase pair.
     *
     * @return multi-record measure responses keyed first by {@link Response#testId()}, then by
     *     {@link Response#phase()}
     */
    public Map<String, Map<Phase, Response>> multiRecordMeasureResponsesByTestAndPhase() {
        return multiRecordMeasureResponses().stream()
                .collect(Collectors.groupingBy(
                        Response::testId,
                        Collectors.toMap(Response::phase, response -> response, (left, right) -> right)));
    }

    /**
     * Filters {@link #responses()} by an arbitrary predicate.
     *
     * @param predicate the predicate responses must satisfy
     * @return the matching responses, in original order
     */
    private List<Response> filter(Predicate<Response> predicate) {
        return responses.stream().filter(predicate).toList();
    }

    /**
     * Substitutes a placeholder for a null or blank value, for use as a grouping key.
     *
     * @param value the raw value, possibly null or blank
     * @return {@code value}, or {@code "<none>"} if it is null or blank
     */
    private static String defaulted(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
