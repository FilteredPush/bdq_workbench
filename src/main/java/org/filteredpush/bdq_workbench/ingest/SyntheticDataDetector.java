/** SyntheticDataDetector.java
 *
 * Scans input records for the BDQ record-level markers of synthetic, modified, and example data.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.SyntheticDataMarkers;

/**
 * Detects the record-level markers the BDQ "Guide to Marking and Identifying Synthetic and Modified
 * Data" (https://rs.tdwg.org/bdq/doc/synthetic/, sections 2.4 and 2.5) asks producers to set.
 *
 * <ul>
 *   <li>Wholly synthetic: {@code dwc:collectionCode} "Synthetic Example" or {@code dwc:collectionID}
 *       {@value #SYNTHETIC_COLLECTION_ID}.
 *   <li>Modified from real data: {@code dwc:collectionCode} "Modified Example",
 *       {@code dwc:collectionID} {@value #MODIFIED_COLLECTION_ID}, or {@code dwc:relationshipOfResource}
 *       "source for modified example record".
 *   <li>Example institution: {@code dwc:institutionCode} "example.org" or {@code dwc:institutionID}
 *       {@code http://example.org/}, which both kinds carry; counted separately only when neither of
 *       the above is present.
 * </ul>
 *
 * <p>Terms are matched by local name, case-insensitively, and values ignoring case and surrounding
 * whitespace. A record is marked when its own row or any of its related rows carries a marker, and
 * is counted once, under its strongest marker. Scanning raw rows (before a dataset view maps
 * terms) means the markers are found even when a view does not carry {@code collectionCode}.
 */
public final class SyntheticDataDetector {

	/** The guide's collection identifier for wholly synthetic example data. */
	static final String SYNTHETIC_COLLECTION_ID = "urn:uuid:0b1b9546-64aa-446b-bd9c-cbb0eacf4332";
	/** The guide's collection identifier for real data with synthetic modifications. */
	static final String MODIFIED_COLLECTION_ID = "urn:uuid:1887c794-7291-4005-8eee-1afbe9d7814e";
	private static final String SYNTHETIC_COLLECTION_CODE = "synthetic example";
	private static final String MODIFIED_COLLECTION_CODE = "modified example";
	private static final String MODIFIED_RELATIONSHIP = "source for modified example record";
	private static final String EXAMPLE_INSTITUTION_CODE = "example.org";
	private static final List<String> EXAMPLE_INSTITUTION_IDS = List.of("http://example.org/", "http://example.org",
			"https://example.org/", "https://example.org");
	/** Marked record identifiers kept as examples for the reader. */
	private static final int SAMPLE_SIZE = 5;

	private SyntheticDataDetector() {
	}

	/**
	 * Scans relational graphs: each core record together with its related rows.
	 *
	 * @param graphs the relational graphs
	 * @return the scan result
	 */
	public static SyntheticDataMarkers scanGraphs(List<RecordGraph> graphs) {
		Tally tally = new Tally();
		for (RecordGraph graph : graphs) {
			Marker marker = markerOf(graph.core());
			for (List<CanonicalRecord> related : graph.relatedByRelation().values()) {
				for (CanonicalRecord row : related) {
					marker = strongest(marker, markerOf(row));
				}
			}
			tally.add(graph.core().id(), marker);
		}
		return tally.result(graphs.size());
	}

	/**
	 * Scans a dataset: its graphs when it has them, otherwise its flat records.
	 *
	 * @param dataset the dataset
	 * @return the scan result
	 */
	public static SyntheticDataMarkers scan(RecordDataset dataset) {
		if (dataset.hasStructuredGraphs()) {
			return scanGraphs(dataset.recordGraphs());
		}
		Tally tally = new Tally();
		dataset.records().forEach(record -> tally.add(record.id(), markerOf(record)));
		return tally.result(dataset.records().size());
	}

	/**
	 * Classifies one row by the markers it carries.
	 *
	 * @param row the row
	 * @return its strongest marker
	 */
	private static Marker markerOf(CanonicalRecord row) {
		String collectionCode = value(row, "collectionCode");
		String collectionId = value(row, "collectionID");
		if (SYNTHETIC_COLLECTION_CODE.equals(collectionCode) || SYNTHETIC_COLLECTION_ID.equals(collectionId)) {
			return Marker.SYNTHETIC;
		}
		if (MODIFIED_COLLECTION_CODE.equals(collectionCode) || MODIFIED_COLLECTION_ID.equals(collectionId)
				|| MODIFIED_RELATIONSHIP.equals(value(row, "relationshipOfResource"))) {
			return Marker.MODIFIED;
		}
		if (EXAMPLE_INSTITUTION_CODE.equals(value(row, "institutionCode"))
				|| EXAMPLE_INSTITUTION_IDS.contains(value(row, "institutionID"))) {
			return Marker.EXAMPLE_INSTITUTION;
		}
		return Marker.NONE;
	}

	/**
	 * Reads a term by local name, normalized for comparison.
	 *
	 * @param row the row
	 * @param localName the term's local name
	 * @return the trimmed, lower-cased value, or {@code ""} when absent
	 */
	private static String value(CanonicalRecord row, String localName) {
		for (Map.Entry<String, String> term : row.terms().entrySet()) {
			if (DarwinCoreTermResolver.localName(term.getKey()).equalsIgnoreCase(localName) && term.getValue() != null) {
				return term.getValue().trim().toLowerCase(Locale.ROOT);
			}
		}
		return "";
	}

	/**
	 * @param left a marker
	 * @param right another marker
	 * @return the stronger of the two
	 */
	private static Marker strongest(Marker left, Marker right) {
		return left.ordinal() <= right.ordinal() ? left : right;
	}

	/** Record-level markers, strongest first. */
	private enum Marker {
		SYNTHETIC, MODIFIED, EXAMPLE_INSTITUTION, NONE
	}

	/** Accumulates marker counts and sample identifiers. */
	private static final class Tally {
		private int synthetic;
		private int modified;
		private int exampleInstitution;
		private final List<String> samples = new ArrayList<>();

		/**
		 * @param recordId the record's identifier
		 * @param marker the record's strongest marker
		 */
		void add(String recordId, Marker marker) {
			switch (marker) {
				case SYNTHETIC -> synthetic++;
				case MODIFIED -> modified++;
				case EXAMPLE_INSTITUTION -> exampleInstitution++;
				default -> {
					return;
				}
			}
			if (samples.size() < SAMPLE_SIZE) {
				samples.add(recordId);
			}
		}

		/**
		 * @param scanned the number of records scanned
		 * @return the scan result
		 */
		SyntheticDataMarkers result(int scanned) {
			return new SyntheticDataMarkers(scanned, synthetic, modified, exampleInstitution, samples);
		}
	}
}
