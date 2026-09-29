package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.filteredpush.bdq_workbench.ingest.DatasetViewIO;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.ingest.RelationalDatasetIngestor;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewMode;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end check that a dataset view with an {@code EXPAND} join reaches per-row execution:
 * a data package with occurrences, their events, and several identifications per occurrence is
 * ingested through a view that flattens event ({@code FIRST_ROW}) and expands identification, and
 * a test reading {@code dateIdentified} and {@code eventDate} (the shape of
 * VALIDATION_DATEIDENTIFIED_INRANGE) then runs once per identification row, with the event date
 * reused from the flat occurrence record.
 */
class ExpandedViewExecutionTest {

	@Test
	void expandedJoinRunsTestOncePerRelatedRowReusingFlattenedTerms(@TempDir Path dir) throws Exception {
		RecordDataset dataset = expandedDataset(dir);

		assertThat(dataset.hasStructuredGraphs()).isTrue();
		assertThat(dataset.inputDescription().viewMode()).isEqualTo(ViewMode.STRUCTURED);
		List<String> invocations = new CopyOnWriteArrayList<>();
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(1, (record, binding, implementation) -> {
			invocations.add(record.id() + ":" + record.terms().get("dateIdentified") + "/" + record.terms().get("eventDate"));
			return response(record.id(), binding);
		}, false);
		Method check = Checks.class.getMethod("check", String.class, String.class);
		List<MethodParameter> parameters = List.of(
				new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, "dwc:dateIdentified", String.class.getName(), true),
				new MethodParameter(1, "p1", ParameterRole.CONSULTED, "dwc:eventDate", String.class.getName(), true));
		DiscoveredImplementation implementation = new DiscoveredImplementation("t1", null, TestType.VALIDATION,
				Phase.PRE_AMENDMENT, Checks.class.getName(), "check", null, parameters, new Checks(), check);

		List<Response> responses = service.execute(dataset, List.of(binding(parameters)), List.of(implementation));

		/* VALIDATION bindings run in both the pre- and post-amendment phases. */
		assertThat(invocations).hasSize(8).containsOnly(
				"occ-1:2021-01-01/2020-06-01",
				"occ-1:1999-01-01/2020-06-01",
				"occ-2:2022-02-02/2020-07-01",
				"occ-3:/2020-06-01");
		assertThat(responses)
				.filteredOn(response -> response.phase() == Phase.PRE_AMENDMENT && response.subjectRef() != null)
				.extracting(response -> response.subjectRef().rowRef())
				/* identification declares no key, so its rows are referenced by position. */
				.containsExactlyInAnyOrder("row-1", "row-2", "row-3");
		assertThat(responses).noneMatch(response -> response.status() == OutcomeStatus.ERROR);
	}

	/**
	 * Writes the event/occurrence/identification data package and a view that flattens event and
	 * expands identification, then ingests it through the view.
	 *
	 * @param dir the directory to write the package into
	 * @return the ingested dataset
	 * @throws Exception if the package cannot be written
	 */
	private static RecordDataset expandedDataset(Path dir) throws Exception {
		Files.writeString(dir.resolve("event.csv"), "eventID,eventDate\nEV-1,2020-06-01\nEV-2,2020-07-01\n",
				StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("occurrence.csv"), "occurrenceID,eventID\nocc-1,EV-1\nocc-2,EV-2\nocc-3,EV-1\n",
				StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("identification.csv"),
				"identificationID,occurrenceID,dateIdentified\n"
						+ "id-1,occ-1,2021-01-01\nid-2,occ-1,1999-01-01\nid-3,occ-2,2022-02-02\n",
				StandardCharsets.UTF_8);
		Path manifest = Files.writeString(dir.resolve("datapackage.json"), """
				{
				  "resources": [
				    { "name": "event", "path": "event.csv",
				      "schema": { "fields": [ { "name": "eventID" }, { "name": "eventDate" } ], "primaryKey": "eventID" } },
				    { "name": "occurrence", "path": "occurrence.csv",
				      "schema": { "fields": [ { "name": "occurrenceID" }, { "name": "eventID" } ],
				                  "primaryKey": "occurrenceID",
				                  "foreignKeys": [ { "fields": "eventID", "reference": { "resource": "event", "fields": "eventID" } } ] } },
				    { "name": "identification", "path": "identification.csv",
				      "schema": { "fields": [ { "name": "identificationID" }, { "name": "occurrenceID" }, { "name": "dateIdentified" } ],
				                  "foreignKeys": [ { "fields": "occurrenceID", "reference": { "resource": "occurrence", "fields": "occurrenceID" } } ] } }
				  ]
				}
				""", StandardCharsets.UTF_8);
		String fingerprint = new RelationalDatasetIngestor().ingest(manifest, "occurrence").schema().schemaFingerprint();
		DatasetView view = new DatasetView(
				"occurrence",
				fingerprint,
				List.of(
						new DatasetViewJoin("event", "event", DatasetViewCardinalityPolicy.FIRST_ROW),
						new DatasetViewJoin("identification", "identification", DatasetViewCardinalityPolicy.EXPAND)),
				List.of(
						new DatasetViewMapping("occurrenceID", "occurrence", "occurrenceID"),
						new DatasetViewMapping("eventDate", "event", "eventDate"),
						new DatasetViewMapping("dateIdentified", "identification", "dateIdentified")));
		Path viewPath = dir.resolve("view.json");
		new DatasetViewIO().save(viewPath, view);

		return new DefaultIngestService().ingest(manifest, "occurrence", viewPath.toString());

	}

	@Test
	void amendmentOnOneExpandedRowWritesBackToThatRowOnly(@TempDir Path dir) throws Exception {
		RecordDataset dataset = expandedDataset(dir);
		List<String> validationInvocations = new CopyOnWriteArrayList<>();
		ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(1, (record, binding, implementation) -> {
			String date = record.terms().get("dateIdentified");
			if (binding.phase() == Phase.AMENDMENT) {
				return "1999-01-01".equals(date)
						? amended(record.id(), binding, Map.of("dateIdentified", "2000-01-01"))
						: response(record.id(), binding);
			}
			validationInvocations.add(record.id() + ":" + date);
			return response(record.id(), binding);
		}, false);
		Method check = Checks.class.getMethod("check", String.class, String.class);
		List<MethodParameter> parameters = List.of(
				new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, "dwc:dateIdentified", String.class.getName(), true),
				new MethodParameter(1, "p1", ParameterRole.CONSULTED, "dwc:eventDate", String.class.getName(), true));
		List<DiscoveredImplementation> discovered = List.of(
				new DiscoveredImplementation("t1", null, TestType.VALIDATION, Phase.PRE_AMENDMENT,
						Checks.class.getName(), "check", null, parameters, new Checks(), check),
				new DiscoveredImplementation("a1", null, TestType.AMENDMENT, Phase.AMENDMENT,
						Checks.class.getName(), "amend", null, parameters, new Checks(),
						Checks.class.getMethod("amend", String.class, String.class)));

		service.execute(dataset, List.of(binding(parameters), amendmentBinding(parameters)), discovered);

		/* The validation runs pre-amendment (first four calls) and post-amendment (last four). */
		assertThat(validationInvocations).hasSize(8);
		assertThat(validationInvocations.subList(4, 8)).containsExactlyInAnyOrder(
				"occ-1:2021-01-01", "occ-1:2000-01-01", "occ-2:2022-02-02", "occ-3:");
	}

	private static ImplementationBinding amendmentBinding(List<MethodParameter> parameters) {
		ImplementationBinding validation = binding(parameters);
		return new ImplementationBinding("a1", TestType.AMENDMENT, validation.implementationClass(),
				"amend", Phase.AMENDMENT, Map.of(), BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY, "test", true, validation.parameterBindings(), List.of());
	}

	private static Response amended(String recordId, ImplementationBinding binding, Map<String, String> amendments) {
		return new Response(recordId, binding.testId(), binding.testType(), binding.implementationClass(),
				binding.implementationMethod(), binding.phase(), binding.parameters(), OutcomeStatus.AMENDED,
				"AMENDED", "", "", "", amendments, java.time.Instant.now(), java.time.Instant.now());
	}

	private static ImplementationBinding binding(List<MethodParameter> parameters) {
		return new ImplementationBinding(
				"t1",
				TestType.VALIDATION,
				Checks.class.getName(),
				"check",
				Phase.PRE_AMENDMENT,
				Map.of(),
				BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY,
				"test",
				true,
				List.of(
						new BoundMethodParameter(parameters.get(0), "dateIdentified", null, true, "Mapped"),
						new BoundMethodParameter(parameters.get(1), "eventDate", null, true, "Mapped")),
				List.of());
	}

	private static Response response(String recordId, ImplementationBinding binding) {
		return new Response(recordId, binding.testId(), binding.testType(), binding.implementationClass(),
				binding.implementationMethod(), binding.phase(), binding.parameters(), OutcomeStatus.PASSED,
				"RUN_HAS_RESULT", "COMPLIANT", "", "", Map.of(), java.time.Instant.now(), java.time.Instant.now());
	}

	/** Stand-in implementation class; the test's execution adapter never invokes it. */
	public static class Checks {
		/**
		 * @param dateIdentified ignored
		 * @param eventDate ignored
		 * @return {@code null}
		 */
		public Object check(String dateIdentified, String eventDate) {
			return null;
		}

		/**
		 * @param dateIdentified ignored
		 * @param eventDate ignored
		 * @return {@code null}
		 */
		public Object amend(String dateIdentified, String eventDate) {
			return null;
		}
	}
}
