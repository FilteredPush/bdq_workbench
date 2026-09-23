/** DatasetViewCardinalityPolicy.java
 *
 * Cardinality handling choices when flattening related rows.
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
 * Per-join policy for handling multiple related rows.
 */
public enum DatasetViewCardinalityPolicy {
	/** Concatenate all matching row values in deterministic related-row order using {@code " | "}. */
	AGGREGATE,

	/** Use only the first matching row value in deterministic related-row order. */
	FIRST_ROW,

	/** Keep flattening but emit a diagnostic when multiple related rows are present. */
	REJECT
}
