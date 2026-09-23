/** SourceCell.java
 *
 * Provenance for one value in a flattened record.
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
 * Source location of a flattened term value.
 *
 * @param table the source table/resource label
 * @param sourceLocation the source file or manifest path for the table
 * @param rowRef deterministic source row reference within the table
 * @param column the source column name
 * @param term the flattened Darwin Core term that took this value
 */
public record SourceCell(
		String table,
		String sourceLocation,
		String rowRef,
		String column,
		String term) {
}
