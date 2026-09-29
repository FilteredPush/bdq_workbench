package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;
import org.filteredpush.bdq_workbench.model.TableSchema;
import org.junit.jupiter.api.Test;

/**
 * Tests the dataset view built when a multi-table dataset is run without a view file.
 */
class AutomaticDatasetViewsTest {

	@Test
	void singleValuedRelationsJoinFirstRowAndEveryColumnIsMapped() {
		DatasetView view = AutomaticDatasetViews.build(relational(1), Map.of(), JoinPolicyResolver.NONE);

		assertThat(view.grainTable()).isEqualTo("occurrence");
		assertThat(view.joins()).extracting(DatasetViewJoin::sourceTable, DatasetViewJoin::cardinalityPolicy)
				.containsExactly(
						tuple("event", DatasetViewCardinalityPolicy.FIRST_ROW),
						tuple("identification", DatasetViewCardinalityPolicy.FIRST_ROW));
		assertThat(view.mappings()).extracting(DatasetViewMapping::term, DatasetViewMapping::sourceTable)
				.contains(
						tuple("basisOfRecord", "occurrence"),
						tuple("countryCode", "event"),
						tuple("eventDate", "event"),
						tuple("dateIdentified", "identification"),
						tuple("scientificName", "occurrence"));
		/* Join keys come from the grain, never from the related table. */
		assertThat(view.mappings()).noneMatch(mapping -> mapping.sourceTable().equals("identification")
				&& mapping.sourceColumn().equals("occurrenceID"));
	}

	@Test
	void multiValuedRelationIsAskedOfTheResolver() {
		List<ViewRelation> asked = new ArrayList<>();
		DatasetView view = AutomaticDatasetViews.build(relational(2), Map.of(), (grain, undecided) -> {
			asked.addAll(undecided);
			return Map.of("identification", DatasetViewCardinalityPolicy.EXPAND);
		});

		assertThat(asked).singleElement().satisfies(relation -> {
			assertThat(relation.sourceTable()).isEqualTo("identification");
			assertThat(relation.maxRowsPerCoreRecord()).isEqualTo(2);
		});
		assertThat(view.joins()).extracting(DatasetViewJoin::cardinalityPolicy)
				.containsExactly(DatasetViewCardinalityPolicy.FIRST_ROW, DatasetViewCardinalityPolicy.EXPAND);
		/* Both the grain's scientificName and each identification's are mapped, so both are tested. */
		assertThat(view.mappings()).filteredOn(mapping -> mapping.term().equals("scientificName"))
				.extracting(DatasetViewMapping::sourceTable)
				.containsExactly("occurrence", "identification");
	}

	@Test
	void undecidedMultiValuedRelationStopsWithInstructions() {
		assertThatThrownBy(() -> AutomaticDatasetViews.build(relational(2), Map.of(), JoinPolicyResolver.NONE))
				.isInstanceOf(DatasetViewRequiredException.class)
				.hasMessageContaining("identification: up to 2 rows per occurrence record (1 records have more than one)")
				.hasMessageContaining("--join-policy identification=EXPAND")
				.satisfies(error -> assertThat(((DatasetViewRequiredException) error).undecided())
						.extracting(ViewRelation::sourceTable)
						.containsExactly("identification"));
	}

	@Test
	void suppliedPoliciesAreUsedAndUnknownTablesRejected() {
		DatasetView view = AutomaticDatasetViews.build(relational(2),
				Map.of("Identification", DatasetViewCardinalityPolicy.AGGREGATE), JoinPolicyResolver.NONE);

		assertThat(view.joins()).extracting(DatasetViewJoin::cardinalityPolicy)
				.containsExactly(DatasetViewCardinalityPolicy.FIRST_ROW, DatasetViewCardinalityPolicy.AGGREGATE);
		assertThatThrownBy(() -> AutomaticDatasetViews.build(relational(1),
				Map.of("measurement", DatasetViewCardinalityPolicy.EXPAND), JoinPolicyResolver.NONE))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("not directly related to the grain table occurrence");
	}

	/**
	 * Builds an occurrence-grain relational result with one event per occurrence and
	 * {@code identificationsForFirst} identifications for the first occurrence.
	 */
	private static RelationalIngestResult relational(int identificationsForFirst) {
		DatasetSchema schema = new DatasetSchema(
				List.of(
						new TableSchema("event", "event", "EVENT", "eventID",
								List.of("eventID", "eventDate", "countryCode")),
						new TableSchema("occurrence", "occurrence", "OCCURRENCE", "occurrenceID",
								List.of("occurrenceID", "eventID", "basisOfRecord", "scientificName")),
						new TableSchema("identification", "identification", "OTHER", "identificationID",
								List.of("occurrenceID", "scientificName", "dateIdentified"))),
				List.of(
						new RelationshipSchema("occurrence", "eventID", "event", "eventID", "occurrence"),
						new RelationshipSchema("identification", "occurrenceID", "occurrence", "occurrenceID",
								"identification")),
				"fp");
		List<CanonicalRecord> identifications = new ArrayList<>();
		for (int index = 0; index < identificationsForFirst; index++) {
			identifications.add(new CanonicalRecord("row-" + (index + 1), Map.of("occurrenceID", "occ-1")));
		}
		CanonicalRecord event = new CanonicalRecord("EV-1", Map.of("eventID", "EV-1"));
		return new RelationalIngestResult(
				List.of(
						new RecordGraph(new CanonicalRecord("occ-1", Map.of("occurrenceID", "occ-1")),
								Map.of("event", List.of(event), "identification", identifications)),
						new RecordGraph(new CanonicalRecord("occ-2", Map.of("occurrenceID", "occ-2")),
								Map.of("event", List.of(event), "identification", List.of()))),
				schema,
				List.of(),
				"occurrence",
				Map.of());
	}
}
