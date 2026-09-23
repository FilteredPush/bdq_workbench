package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.SourceCell;
import org.junit.jupiter.api.Test;

class ViewFlattenerTest {

	@Test
	void aggregatePolicyConcatenatesDeterministicallyAndRetainsProvenance() {
		CanonicalRecord core = new CanonicalRecord("occ-1", Map.of("occurrenceID", "occ-1"));
		CanonicalRecord idA = new CanonicalRecord("id-1", Map.of("scientificName", "Abies"));
		CanonicalRecord idB = new CanonicalRecord("id-2", Map.of("scientificName", "Picea"));
		RecordGraph graph = new RecordGraph(core, Map.of("identification", List.of(idA, idB)));
		RelationalIngestResult relational = new RelationalIngestResult(
				List.of(graph),
				new DatasetSchema(List.of(), List.of(), "fp"),
				List.of());
		DatasetView view = new DatasetView(
				"core",
				"fp",
				List.of(new DatasetViewJoin("identification", "identification", DatasetViewCardinalityPolicy.AGGREGATE)),
				List.of(new DatasetViewMapping("scientificName", "identification", "scientificName")));

		ViewFlattenResult flattened = new ViewFlattener().flatten(relational, view);

		assertThat(flattened.dataset().records()).hasSize(1);
		assertThat(flattened.dataset().records().get(0).terms().get("scientificName"))
				.isEqualTo("Abies | Picea");
		assertThat(flattened.dataset().records().get(0).provenanceByTerm().get("scientificName"))
				.extracting(SourceCell::rowRef)
				.containsExactly("id-1", "id-2");
	}

	@Test
	void firstRowPolicyUsesFirstRowDeterministically() {
		CanonicalRecord core = new CanonicalRecord("occ-1", Map.of("occurrenceID", "occ-1"));
		CanonicalRecord idA = new CanonicalRecord("id-1", Map.of("scientificName", "Abies"));
		CanonicalRecord idB = new CanonicalRecord("id-2", Map.of("scientificName", "Picea"));
		RecordGraph graph = new RecordGraph(core, Map.of("identification", List.of(idA, idB)));
		RelationalIngestResult relational = new RelationalIngestResult(
				List.of(graph),
				new DatasetSchema(List.of(), List.of(), "fp"),
				List.of());
		DatasetView view = new DatasetView(
				"core",
				"fp",
				List.of(new DatasetViewJoin("identification", "identification", DatasetViewCardinalityPolicy.FIRST_ROW)),
				List.of(new DatasetViewMapping("scientificName", "identification", "scientificName")));

		ViewFlattenResult flattened = new ViewFlattener().flatten(relational, view);

		assertThat(flattened.dataset().records().get(0).terms().get("scientificName")).isEqualTo("Abies");
	}

	@Test
	void rejectPolicyAddsDiagnosticInsteadOfCrashing() {
		CanonicalRecord core = new CanonicalRecord("occ-1", Map.of("occurrenceID", "occ-1"));
		CanonicalRecord idA = new CanonicalRecord("id-1", Map.of("scientificName", "Abies"));
		CanonicalRecord idB = new CanonicalRecord("id-2", Map.of("scientificName", "Picea"));
		RecordGraph graph = new RecordGraph(core, Map.of("identification", List.of(idA, idB)));
		RelationalIngestResult relational = new RelationalIngestResult(
				List.of(graph),
				new DatasetSchema(List.of(), List.of(), "fp"),
				List.of());
		DatasetView view = new DatasetView(
				"core",
				"fp",
				List.of(new DatasetViewJoin("identification", "identification", DatasetViewCardinalityPolicy.REJECT)),
				List.of(new DatasetViewMapping("scientificName", "identification", "scientificName")));

		ViewFlattenResult flattened = new ViewFlattener().flatten(relational, view);

		assertThat(flattened.dataset().records().get(0).terms().get("scientificName")).isEmpty();
		assertThat(flattened.diagnostics()).anyMatch(message -> message.contains("Cardinality conflict"));
	}
}
