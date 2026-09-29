package org.filteredpush.bdq_workbench.reporting;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.InputTable;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewMode;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * Shared execution-summary fixtures for the input-view sections of the structured reports.
 *
 * <p>The structured fixture has an {@code event} grain table, an {@code occurrence} child that
 * supplies a bound term and has up to three rows per event, a {@code measurement} child that
 * supplies no bound term, a {@code media} table unrelated to the grain that carries a bound term,
 * and an {@code audit} table unrelated to the grain that carries none.
 */
final class InputViewFixtures {

	private InputViewFixtures() {
	}

	/**
	 * @return a summary over a structured view with multiplicity present
	 */
	static ExecutionSummary structuredSummary() {
		return summary(new DatasetInputDescription(
				ViewMode.STRUCTURED,
				"automatic relational ingest",
				"event",
				2,
				tables(),
				relationships(),
				List.of(
						new ViewRelation("occurrence", "occurrence", null, 2, 1, 3, 4, List.of()),
						new ViewRelation("measurement", "measurement", null, 1, 0, 1, 1, List.of())),
				List.of()));
	}

	/**
	 * @return a summary over a flattened view that joins occurrence by first row
	 */
	static ExecutionSummary flattenedSummary() {
		return summary(new DatasetInputDescription(
				ViewMode.FLATTENED,
				"dataset view file view.json",
				"event",
				2,
				tables(),
				relationships(),
				List.of(new ViewRelation("occurrence", "occurrence", DatasetViewCardinalityPolicy.FIRST_ROW,
						2, 1, 3, 4, List.of("scientificName"))),
				List.of("eventID", "eventDate")));
	}

	/**
	 * Builds a summary around one input description, with bindings reading eventDate and
	 * scientificName.
	 *
	 * @param description the input description
	 * @return the execution summary
	 */
	private static ExecutionSummary summary(DatasetInputDescription description) {
		RecordDataset dataset = new RecordDataset(
				List.of(
						new CanonicalRecord("EV-1", Map.of("eventID", "EV-1", "eventDate", "2020-01-01")),
						new CanonicalRecord("EV-2", Map.of("eventID", "EV-2", "eventDate", "2020-01-02"))),
				List.of(),
				description);
		return new ExecutionSummary(
				List.of(),
				new ExecutionSummaryMetadata("uc", "Use case", "dataset.zip", 2, 2, Map.of(), Map.of()),
				dataset,
				List.of(binding("urn:test:date", "dwc:eventDate"), binding("urn:test:name", "dwc:scientificName")));
	}

	private static List<InputTable> tables() {
		return List.of(
				new InputTable("event", "EVENT", 2, List.of("eventID", "eventDate")),
				new InputTable("occurrence", "OCCURRENCE", 4, List.of("occurrenceID", "eventID", "scientificName")),
				new InputTable("measurement", "OTHER", 1, List.of("measurementID", "eventID", "measurementValue")),
				new InputTable("media", "OTHER", 7, List.of("mediaID", "scientificName")),
				new InputTable("audit", "OTHER", 9, List.of("auditID", "note")));
	}

	private static List<RelationshipSchema> relationships() {
		return List.of(
				new RelationshipSchema("occurrence", "eventID", "event", "eventID", "occurrence"),
				new RelationshipSchema("measurement", "eventID", "event", "eventID", "measurement"),
				new RelationshipSchema("audit", "mediaID", "media", "mediaID", "audit"));
	}

	private static ImplementationBinding binding(String testId, String term) {
		MethodParameter actedUpon = new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, term,
				String.class.getName(), true);
		return new ImplementationBinding(
				testId,
				TestType.VALIDATION,
				"org.example.Impl",
				"validate",
				Phase.PRE_AMENDMENT,
				Map.of(),
				BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY,
				"default",
				true,
				List.of(new BoundMethodParameter(actedUpon, term, null, true, "Mapped")),
				List.of());
	}
}
