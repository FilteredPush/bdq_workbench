package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.SyntheticDataMarkers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests detection of the BDQ synthetic/modified/example data markers.
 */
class SyntheticDataDetectorTest {

	@Test
	void classifiesEachRecordByItsStrongestMarker() {
		SyntheticDataMarkers markers = SyntheticDataDetector.scan(new RecordDataset(List.of(
				record("s1", Map.of("collectionCode", " synthetic example ", "institutionCode", "example.org")),
				record("s2", Map.of("dwc:collectionID", SyntheticDataDetector.SYNTHETIC_COLLECTION_ID)),
				record("m1", Map.of("collectionCode", "Modified Example")),
				record("m2", Map.of("relationshipOfResource", "source for modified example record")),
				record("m3", Map.of("collectionID", SyntheticDataDetector.MODIFIED_COLLECTION_ID)),
				record("e1", Map.of("institutionID", "http://example.org/")),
				record("r1", Map.of("collectionCode", "Herpetology", "institutionCode", "MCZ")))));

		assertThat(markers.recordsScanned()).isEqualTo(7);
		assertThat(markers.syntheticRecords()).isEqualTo(2);
		assertThat(markers.modifiedRecords()).isEqualTo(3);
		assertThat(markers.exampleInstitutionRecords()).isEqualTo(1);
		assertThat(markers.sampleRecordIds()).containsExactly("s1", "s2", "m1", "m2", "m3");
		assertThat(markers.summaryLine()).isEqualTo("6 of 7 input record(s) are marked as synthetic or modified example "
				+ "data (2 wholly synthetic, 3 modified from real data, 1 with the example.org institution only)");
		assertThat(markers.warning()).contains("do not use these results").contains("Examples: s1, s2, m1, m2, m3.");
	}

	@Test
	void markerOnARelatedRowMarksItsCoreRecord() {
		CanonicalRecord core = record("occ-1", Map.of("occurrenceID", "occ-1"));
		SyntheticDataMarkers markers = SyntheticDataDetector.scanGraphs(List.of(
				new RecordGraph(core, Map.of("event", List.of(record("ev-1", Map.of("collectionCode", "Synthetic Example"))))),
				new RecordGraph(record("occ-2", Map.of()), Map.of())));

		assertThat(markers.syntheticRecords()).isEqualTo(1);
		assertThat(markers.sampleRecordIds()).containsExactly("occ-1");
	}

	@Test
	void cleanInputReportsNoneDetected() {
		SyntheticDataMarkers markers = SyntheticDataDetector.scan(new RecordDataset(List.of(
				record("r1", Map.of("institutionCode", "MCZ")))));

		assertThat(markers.found()).isFalse();
		assertThat(markers.summaryLine()).isEqualTo("none detected in 1 input record(s)");
		assertThat(SyntheticDataMarkers.notScanned().summaryLine()).isEqualTo("not checked");
	}

	@Test
	void ingestScansRawRowsEvenWhenTheViewDoesNotMapTheMarkerTerms(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("occurrence.csv"),
				"occurrenceID,collectionCode,scientificName\nocc-1,Synthetic Example,Aus bus\nocc-2,MCZ,Bus cus\n",
				StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("identification.csv"),
				"identificationID,occurrenceID,dateIdentified\nid-1,occ-1,2020\n", StandardCharsets.UTF_8);
		Path manifest = Files.writeString(dir.resolve("datapackage.json"), """
				{ "resources": [
				  { "name": "occurrence", "path": "occurrence.csv",
				    "schema": { "fields": [ { "name": "occurrenceID" }, { "name": "collectionCode" }, { "name": "scientificName" } ],
				                "primaryKey": "occurrenceID" } },
				  { "name": "identification", "path": "identification.csv",
				    "schema": { "fields": [ { "name": "identificationID" }, { "name": "occurrenceID" }, { "name": "dateIdentified" } ],
				                "foreignKeys": [ { "fields": "occurrenceID", "reference": { "resource": "occurrence", "fields": "occurrenceID" } } ] } }
				] }
				""", StandardCharsets.UTF_8);
		String fingerprint = new RelationalDatasetIngestor().ingest(manifest, "occurrence").schema().schemaFingerprint();
		Path view = dir.resolve("view.json");
		new DatasetViewIO().save(view, new org.filteredpush.bdq_workbench.model.DatasetView("occurrence", fingerprint,
				List.of(), List.of(new org.filteredpush.bdq_workbench.model.DatasetViewMapping(
						"scientificName", "occurrence", "scientificName"))));

		RecordDataset dataset = new DefaultIngestService().ingest(manifest, "occurrence", view.toString());

		assertThat(dataset.records().get(0).terms()).doesNotContainKey("collectionCode");
		assertThat(dataset.inputDescription().syntheticMarkers().syntheticRecords()).isEqualTo(1);
		assertThat(dataset.inputDescription().syntheticMarkers().recordsScanned()).isEqualTo(2);
	}

	private static CanonicalRecord record(String id, Map<String, String> terms) {
		return new CanonicalRecord(id, terms);
	}
}
