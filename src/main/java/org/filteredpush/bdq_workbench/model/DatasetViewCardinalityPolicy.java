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
 * Per-join policy for handling a grain record that has more than one related row in a joined
 * table.
 *
 * <p>Three policies flatten: they reduce each relation to at most one value per mapped term, so
 * every grain row becomes exactly one flat record. {@link #EXPAND} does not flatten: it keeps every
 * related row, so tests whose inputs come from that table run once per related row.
 *
 * <p>Choosing a policy only matters where multiplicity exists. For a relation that has at most one
 * row per grain record (typically a parent table, such as the event of an occurrence),
 * {@link #FIRST_ROW} and {@link #EXPAND} behave identically in the values tests see.
 */
public enum DatasetViewCardinalityPolicy {
	/**
	 * Keeps every related row. The grain record stays one flat record for terms from the grain and
	 * flattened tables, while this table's rows are retained as related rows carrying the view's
	 * mapped terms. A test whose inputs include a term from this table runs once per related row
	 * (with the grain record's terms alongside), and VALIDATION/ISSUE results are also rolled up
	 * to the grain record. A test whose inputs span two different {@code EXPAND} tables cannot be
	 * evaluated and reports an error, so expand only the tables whose rows each need testing.
	 */
	EXPAND("expand: test each related row",
			"Keep every related row; tests reading this table's terms run once per row, "
					+ "and VALIDATION/ISSUE results roll up to the grain record."),

	/**
	 * Uses only the first related row, in deterministic related-row order; the others are ignored.
	 * This is the default when flattening, since it always yields values of the shape each term
	 * expects.
	 */
	FIRST_ROW("first row (default)",
			"Flatten: use the first related row and ignore the rest."),

	/**
	 * Concatenates the values of every related row, in deterministic related-row order, with
	 * {@code " | "}. The combined value will usually fail tests on that term, since it is not a
	 * single valid value; use it only for terms read as free text.
	 */
	AGGREGATE("aggregate with \" | \"",
			"Flatten: join all related rows' values with \" | \"; combined values usually fail tests."),

	/**
	 * Leaves the mapped terms empty for any grain record with more than one related row, and
	 * reports a diagnostic for each such record. Grain records with exactly one related row keep
	 * its values.
	 */
	REJECT("reject: empty when multiple",
			"Flatten: leave the terms empty (and warn) for grain records with more than one related row.");

	private final String label;
	private final String description;

	/**
	 * @param label short label shown in the dataset-view builder
	 * @param description one-sentence explanation shown in the dataset-view builder
	 */
	DatasetViewCardinalityPolicy(String label, String description) {
		this.label = label;
		this.description = description;
	}

	/**
	 * @return the short label shown in the dataset-view builder
	 */
	public String label() {
		return label;
	}

	/**
	 * @return a one-sentence explanation of what the policy does
	 */
	public String description() {
		return description;
	}

	/**
	 * @return {@code true} when this policy reduces the relation to one value per term
	 */
	public boolean flattens() {
		return this != EXPAND;
	}
}
