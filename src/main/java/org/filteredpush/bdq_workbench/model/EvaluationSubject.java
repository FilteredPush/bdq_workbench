/** EvaluationSubject.java
 *
 * Effective subject presented to one structured test invocation.
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

import java.util.Map;

/**
 * Effective subject presented to one structured test invocation.
 *
 * <p>The subject's {@link #effectiveRecord()} overlays any governing subrecord terms onto inherited
 * core terms while retaining the original {@link SourceCell}-level provenance for every value.
 * Flat execution remains the degenerate case: one subject per canonical record, with no
 * {@link SubjectRef}.
 *
 * @param coreRecordId the core record this subject belongs to
 * @param effectiveRecord the effective record view to invoke the implementation against
 * @param governingRecord the mutable source row that supplies this subject's governing grain
 * @param graph the structured graph the subject was expanded from
 * @param subjectRef stable reference to the governing subrecord, or {@code null} for flat/core
 *     grain subjects
 */
public record EvaluationSubject(
		String coreRecordId,
		CanonicalRecord effectiveRecord,
		CanonicalRecord governingRecord,
		RecordGraph graph,
		SubjectRef subjectRef) {

	/**
	 * Creates the degenerate flat/core-grain subject for {@code record}.
	 *
	 * @param record the flat record
	 * @return the corresponding single evaluation subject
	 */
	public static EvaluationSubject flat(CanonicalRecord record) {
		return new EvaluationSubject(record.id(), record, record, new RecordGraph(record, Map.of()), null);
	}

	/**
	 * @return {@code true} when this subject identifies a specific non-core governing subrecord
	 */
	public boolean hasStructuredReference() {
		return subjectRef != null;
	}

	/**
	 * @return a deterministic subject ordering key
	 */
	public String sortKey() {
		return subjectRef == null ? "" : subjectRef.sortKey();
	}
}
