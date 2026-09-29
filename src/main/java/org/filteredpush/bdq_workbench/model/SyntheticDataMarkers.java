/** SyntheticDataMarkers.java
 *
 * Counts of input records carrying the BDQ markers for synthetic, modified, or example data.
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

import java.util.ArrayList;
import java.util.List;

/**
 * Result of scanning an input dataset for the record-level markers defined by the BDQ "Guide to
 * Marking and Identifying Synthetic and Modified Data" (https://rs.tdwg.org/bdq/doc/synthetic/).
 *
 * <p>That guide says consumers MUST NOT treat records so marked as representing actual
 * biodiversity data, so reports state plainly when any are present. Each input record is counted
 * once, under its strongest marker: wholly synthetic, then modified from real data, then carrying
 * only the example institution ({@code example.org}).
 *
 * @param recordsScanned the number of input records scanned; {@code 0} when no scan was made
 * @param syntheticRecords records marked as wholly synthetic example data
 * @param modifiedRecords records marked as real data with synthetic modifications
 * @param exampleInstitutionRecords records marked only by the example institution
 * @param sampleRecordIds identifiers of a few marked records, for the reader to check
 */
public record SyntheticDataMarkers(
		int recordsScanned,
		int syntheticRecords,
		int modifiedRecords,
		int exampleInstitutionRecords,
		List<String> sampleRecordIds) {

	private static final SyntheticDataMarkers NOT_SCANNED = new SyntheticDataMarkers(0, 0, 0, 0, List.of());

	/**
	 * Canonical constructor; copies the sample identifiers defensively.
	 */
	public SyntheticDataMarkers {
		sampleRecordIds = List.copyOf(sampleRecordIds == null ? List.of() : sampleRecordIds);
	}

	/**
	 * @return the result used when no scan was made
	 */
	public static SyntheticDataMarkers notScanned() {
		return NOT_SCANNED;
	}

	/**
	 * @return {@code true} when a scan was made
	 */
	public boolean scanned() {
		return recordsScanned > 0;
	}

	/**
	 * @return {@code true} when any record carries a synthetic, modified, or example marker
	 */
	public boolean found() {
		return markedRecords() > 0;
	}

	/**
	 * @return the number of records carrying any marker
	 */
	public int markedRecords() {
		return syntheticRecords + modifiedRecords + exampleInstitutionRecords;
	}

	/**
	 * Describes the scan result in one line, for report summaries.
	 *
	 * @return e.g. {@code "12 of 40 input records are marked as synthetic or modified example data
	 *     (8 wholly synthetic, 4 modified from real data)"}, or {@code "none detected"}
	 */
	public String summaryLine() {
		if (!scanned()) {
			return "not checked";
		}
		if (!found()) {
			return "none detected in " + recordsScanned + " input record(s)";
		}
		List<String> parts = new ArrayList<>();
		if (syntheticRecords > 0) {
			parts.add(syntheticRecords + " wholly synthetic");
		}
		if (modifiedRecords > 0) {
			parts.add(modifiedRecords + " modified from real data");
		}
		if (exampleInstitutionRecords > 0) {
			parts.add(exampleInstitutionRecords + " with the example.org institution only");
		}
		return markedRecords() + " of " + recordsScanned + " input record(s) are marked as synthetic or modified "
				+ "example data (" + String.join(", ", parts) + ")";
	}

	/**
	 * Explains why the markers matter, for the warning shown when any are found.
	 *
	 * @return the warning text
	 */
	public String warning() {
		return "This input contains records marked as synthetic or modified example data under the BDQ Guide to "
				+ "Marking and Identifying Synthetic and Modified Data. Such records do not represent actual "
				+ "biodiversity data: do not use these results, or the data, in biodiversity analyses."
				+ (sampleRecordIds.isEmpty() ? "" : " Examples: " + String.join(", ", sampleRecordIds) + ".");
	}
}
