/** StructuredSubjectSelectors.java
 *
 * Shared structured-subject selector formatting helpers for reporting exporters.
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
package org.filteredpush.bdq_workbench.reporting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.filteredpush.bdq_workbench.model.SubjectRef;

/**
 * Shared helpers for row-selector-based structured subject reporting.
 */
final class StructuredSubjectSelectors {

	private static final Pattern SYNTHETIC_ROW_REF_PATTERN = Pattern.compile("row-(\\d+)");

	private StructuredSubjectSelectors() {
	}

	/**
	 * Reports whether a subject reference carries enough provenance to mint an unambiguous selector.
	 *
	 * @param subjectRef the subject reference to inspect
	 * @return {@code true} when both source location and row reference are present
	 */
	static boolean hasStructuredSelector(SubjectRef subjectRef) {
		return subjectRef != null
				&& subjectRef.sourceLocation() != null
				&& !subjectRef.sourceLocation().isBlank()
				&& subjectRef.rowRef() != null
				&& !subjectRef.rowRef().isBlank();
	}

	/**
	 * Returns the core record ID that should own a structured target.
	 *
	 * @param fallbackRecordId the response's record ID fallback
	 * @param subjectRef the structured subject reference
	 * @return the owning core record ID
	 */
	static String ownerRecordId(String fallbackRecordId, SubjectRef subjectRef) {
		if (subjectRef != null && subjectRef.coreRecordId() != null && !subjectRef.coreRecordId().isBlank()) {
			return subjectRef.coreRecordId();
		}
		return fallbackRecordId;
	}

	/**
	 * Derives a stable selector fragment from a row reference.
	 *
	 * @param rowRef the stored provenance row reference
	 * @return a row-position fragment when the reference is synthetic, otherwise a row-ref fragment
	 */
	static String selectorValue(String rowRef) {
		Matcher matcher = SYNTHETIC_ROW_REF_PATTERN.matcher(rowRef);
		if (matcher.matches()) {
			return "row=" + matcher.group(1);
		}
		return "rowRef=" + rowRef;
	}

	/**
	 * Chooses a human-readable label for a structured subject.
	 *
	 * @param subjectRef the structured subject reference to label
	 * @return the first non-blank label available, or {@code "<subject>"} when none are present
	 */
	static String subjectLabel(SubjectRef subjectRef) {
		String relationName = subjectRef == null ? null : subjectRef.relationName();
		if (relationName != null && !relationName.isBlank()) {
			return relationName;
		}
		String sourceTable = subjectRef == null ? null : subjectRef.sourceTable();
		if (sourceTable != null && !sourceTable.isBlank()) {
			return sourceTable;
		}
		return "<subject>";
	}

	/**
	 * Renders a concise source-location-plus-selector display label.
	 *
	 * @param subjectRef the structured subject reference to render
	 * @return the display label
	 */
	static String selectorLabel(SubjectRef subjectRef) {
		String source = subjectRef == null || subjectRef.sourceLocation() == null || subjectRef.sourceLocation().isBlank()
				? "<unknown source>"
				: subjectRef.sourceLocation();
		String row = subjectRef == null || subjectRef.rowRef() == null || subjectRef.rowRef().isBlank()
				? "<selector unavailable>"
				: selectorValue(subjectRef.rowRef());
		return source + "#" + row;
	}
}
