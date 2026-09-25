/** SubjectRef.java
 *
 * Stable reference to one structured evaluation subject.
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

/**
 * Stable reference to one structured evaluation subject.
 *
 * @param coreRecordId the core record this subject belongs to
 * @param relationName the governing relation/table for this subject
 * @param sourceTable the source table/resource containing the governing row
 * @param sourceLocation the source file or manifest path for the governing row
 * @param rowRef the stable source-row reference supplied by ingest provenance
 */
public record SubjectRef(
		String coreRecordId,
		String relationName,
		String sourceTable,
		String sourceLocation,
		String rowRef) {

	/**
	 * @return a stable lexical key suitable for deterministic ordering
	 */
	public String sortKey() {
		return String.join(
				"\u0001",
				nullSafe(coreRecordId),
				nullSafe(relationName),
				nullSafe(sourceTable),
				nullSafe(sourceLocation),
				nullSafe(rowRef));
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}
}
