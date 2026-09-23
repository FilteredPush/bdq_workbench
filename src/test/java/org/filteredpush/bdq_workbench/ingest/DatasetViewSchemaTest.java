package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;
import org.filteredpush.bdq_workbench.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetViewSchemaTest {

	@Test
	void schemaFingerprintIsStableForEquivalentShapes() {
		List<TableSchema> first = List.of(
				new TableSchema("occurrence", "occurrence", "OCCURRENCE", "occurrenceID",
						List.of("occurrenceID", "scientificName")),
				new TableSchema("identification", "identification", "OTHER", "identificationID",
						List.of("scientificName", "occurrenceID")));
		List<TableSchema> second = List.of(
				new TableSchema("identification", "identification", "OTHER", "identificationID",
						List.of("occurrenceID", "scientificName")),
				new TableSchema("occurrence", "occurrence", "OCCURRENCE", "occurrenceID",
						List.of("scientificName", "occurrenceID")));

		assertThat(SchemaFingerprint.of(first, List.of())).isEqualTo(SchemaFingerprint.of(second, List.of()));
	}

	@Test
	void datasetViewRoundTripsToJsonAndValidatesFingerprint(@TempDir Path tempDir) {
		DatasetView view = new DatasetView(
				"occurrence",
				"fingerprint-1",
				List.of(new DatasetViewJoin("identification", "identification", DatasetViewCardinalityPolicy.FIRST_ROW)),
				List.of(new DatasetViewMapping("scientificName", "identification", "scientificName")));
		DatasetViewIO io = new DatasetViewIO();
		Path file = tempDir.resolve("view.json");

		io.save(file, view);
		DatasetView loaded = io.load(file);

		assertThat(loaded).isEqualTo(view);

		DatasetSchema compatible = new DatasetSchema(List.of(), List.of(), "fingerprint-1");
		io.validateCompatibility(loaded, compatible);

		DatasetSchema incompatible = new DatasetSchema(List.of(), List.of(), "fingerprint-2");
		assertThatThrownBy(() -> io.validateCompatibility(loaded, incompatible))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("fingerprint does not match");
	}

	@Test
	void builtInViewIsOnlyAppliedWhenRelationshipsArePresent() {
		DatasetSchema withoutRelationship = new DatasetSchema(
				List.of(
						new TableSchema("event", "event", "EVENT", "eventID", List.of("eventID")),
						new TableSchema("occurrence", "occurrence", "OCCURRENCE", "occurrenceID", List.of("occurrenceID"))),
				List.of(),
				"fp");
		DatasetSchema withRelationship = new DatasetSchema(
				withoutRelationship.tables(),
				List.of(new RelationshipSchema("occurrence", "coreid", "event", "eventID", "occurrence")),
				"fp");

		assertThat(BuiltInDatasetViews.select(withoutRelationship, new java.util.ArrayList<>())).isEmpty();
		assertThat(BuiltInDatasetViews.select(withRelationship, new java.util.ArrayList<>())).isPresent();
	}

	@Test
	void builtInDataPackageViewSourcesScientificNameFromJoinedTableWhenOccurrenceLacksColumn() {
		DatasetSchema schema = new DatasetSchema(
				List.of(
						new TableSchema("occurrence", "occurrence", "OCCURRENCE", "occurrenceID",
								List.of("occurrenceID", "eventDate", "decimalLatitude", "decimalLongitude")),
						new TableSchema("identification", "identification", "OTHER", "identificationID",
								List.of("occurrenceID", "scientificName"))),
				List.of(new RelationshipSchema(
						"identification",
						"occurrenceID",
						"occurrence",
						"occurrenceID",
						"identification")),
				"fp");

		DatasetView view = BuiltInDatasetViews.select(schema, new java.util.ArrayList<>()).orElseThrow();

		assertThat(view.mappings())
				.anySatisfy(mapping -> {
					assertThat(mapping.term()).isEqualTo("scientificName");
					assertThat(mapping.sourceTable()).isEqualTo("identification");
					assertThat(mapping.sourceColumn()).isEqualTo("scientificName");
				});
	}

	@Test
	void viewLoadFailureIsReportedAsAppException(@TempDir Path tempDir) {
		DatasetViewIO io = new DatasetViewIO();
		Path missing = tempDir.resolve("missing-view.json");

		assertThatThrownBy(() -> io.load(missing))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("Unable to read dataset view file");
	}

	@Test
	void viewSaveFailureIsReportedAsAppException(@TempDir Path tempDir) {
		DatasetViewIO io = new DatasetViewIO();
		DatasetView view = new DatasetView("occurrence", "fp", List.of(), List.of());
		Path invalid = tempDir.resolve("missing").resolve("view.json");

		assertThatThrownBy(() -> io.save(invalid, view))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("Unable to save dataset view file");
	}
}
