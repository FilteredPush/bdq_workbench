/** TableSchema.java
 *
 * Table discovery metadata.
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

/**
 * One discovered table/resource shape.
 *
 * @param name stable table name used in view definitions
 * @param label human-readable label
 * @param rowType detected Darwin Core row type name
 * @param identifierColumn row identifier column, if known
 * @param columns declared column names
 */
public record TableSchema(
		String name,
		String label,
		String rowType,
		String identifierColumn,
		List<String> columns) {

	/**
	 * Canonical constructor; copies columns defensively.
	 */
	public TableSchema {
		columns = List.copyOf(columns);
	}
}
