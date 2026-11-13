/** DimensionColors.java
 *
 * Colors for data quality dimensions in the overview charts of the GUI and the reports.
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

import java.awt.Color;
import java.util.List;

/** Colors that identify a measure's data quality dimension, shared by the overview charts. */
public final class DimensionColors {
	/** Dimensions in legend order. */
	public static final List<String> KNOWN = List.of("Completeness", "Conformance", "Consistency", "Likeliness");

	private DimensionColors() {
	}

	/**
	 * @param dimension a dimension's local name, possibly empty
	 * @return its color; gray when unknown
	 */
	public static Color colorFor(String dimension) {
		return switch (dimension == null ? "" : dimension) {
			case "Completeness" -> Color.decode("#1565c0");
			case "Conformance" -> Color.decode("#6a1b9a");
			case "Consistency" -> Color.decode("#00897b");
			case "Likeliness" -> Color.decode("#ef6c00");
			default -> Color.decode("#757575");
		};
	}

	/**
	 * @param dimension a dimension's local name, possibly empty
	 * @return its legend label
	 */
	public static String labelFor(String dimension) {
		return dimension == null || dimension.isBlank() ? "No dimension" : dimension;
	}
}
