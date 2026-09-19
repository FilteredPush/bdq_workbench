/** CoreTableCandidate.java
 *
 * One table a dataset offers as the core table a run could be executed against.
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
package org.filteredpush.bdq_workbench.ingest;

import java.util.List;

/**
 * One table a dataset offers as the core table a run could be executed against.
 *
 * <p>A Darwin Core Archive offers its {@code <core>} and each {@code <extension>}; a Darwin Core
 * Data Package offers each of its tabular resources. {@link CoreTableSelector} ranks these and
 * picks one. The format-specific descriptor needed to actually read the table is carried in
 * {@link #descriptor()}, so the selector stays independent of the input format.
 *
 * @param <T> the format-specific table descriptor type, such as {@link DwcArchiveCoreMeta} or
 *     {@link DataPackageResourceMeta}
 * @param descriptor everything needed to read this table
 * @param label a short human-readable name for this table, for logs and diagnostics
 * @param rowType the kind of Darwin Core row this table holds
 * @param rowTypeEvidence a short description of how {@code rowType} was determined
 * @param declaredCore whether the dataset declares this table as its core, as a Darwin Core
 *     Archive does for exactly one of its tables
 * @param declarationOrder this table's zero-based position in the dataset's descriptor, used as
 *     a last-resort stable tie-breaker
 * @param aliases alternative names by which a user may request this table
 */
public record CoreTableCandidate<T>(
		T descriptor,
		String label,
		DatasetRowType rowType,
		String rowTypeEvidence,
		boolean declaredCore,
		int declarationOrder,
		List<String> aliases) {

	/**
	 * Canonical constructor; copies {@code aliases} defensively.
	 */
	public CoreTableCandidate {
		aliases = List.copyOf(aliases == null ? List.of() : aliases);
	}

	/**
	 * Renders this candidate for a log line or a diagnostic message.
	 *
	 * @return a short description of this table and its identified row type
	 */
	public String describe() {
		return label + " [rowType=" + rowType + " (" + rowTypeEvidence + ")"
				+ (declaredCore ? ", declared core" : "") + "]";
	}
}
