package org.filteredpush.bdq_workbench.reporting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SourceCell;
import org.filteredpush.bdq_workbench.model.SourceRow;
import org.filteredpush.bdq_workbench.model.SubjectRef;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * A small but complete run for the quality-control reports.
 *
 * <p>Three occurrences: {@code occ-1} (MCZ:Herp:A-1, two identifications), {@code occ-2}
 * (dataset Survey, catalog 77), and {@code occ-3} (no catalog number). The geodetic datum
 * validation fails for occ-1 and occ-3 before amendment and passes for all after the datum
 * amendment; the country validation cannot assess occ-1 (internal prerequisites) or occ-3
 * (external prerequisites); the scientific name validation runs per identification and fails for
 * one of occ-1's two. {@code locality} is present but always empty and
 * {@code minimumDepthInMeters} is absent. QA measures cover the datum and country validations, so
 * only occ-2 has quality for the use case.
 */
final class ReportFixtures {

	static final String DATUM = "urn:test:datum";
	static final String COUNTRY = "urn:test:country";
	static final String NAME = "urn:test:name";
	static final String LOCALITY = "urn:test:locality";
	static final String DATUM_AMENDMENT = "urn:test:datum-amendment";
	static final String UNRESOLVED = "urn:test:unresolved";

	private ReportFixtures() {
	}

	/**
	 * @return the run summary
	 */
	static ExecutionSummary qualityControlRun() {
		CanonicalRecord occ1 = record("occ-1", 2, Map.of("institutionCode", "MCZ", "collectionCode", "Herp",
				"catalogNumber", "A-1", "geodeticDatum", "WGS 84", "countryCode", "", "locality", ""));
		CanonicalRecord occ2 = record("occ-2", 3, Map.of("datasetName", "Survey", "catalogNumber", "77",
				"geodeticDatum", "EPSG:4326", "countryCode", "US", "locality", ""));
		CanonicalRecord occ3 = record("occ-3", 4, Map.of("geodeticDatum", "WGS 84", "countryCode", "", "locality", ""));
		CanonicalRecord id1 = related("row-1", 2, "Aus bus");
		CanonicalRecord id2 = related("row-2", 3, "Aus");
		RecordDataset dataset = new RecordDataset(List.of(occ1, occ2, occ3), List.of(
				new RecordGraph(occ1, Map.of("identification", List.of(id1, id2))),
				new RecordGraph(occ2, Map.of("identification", List.of())),
				new RecordGraph(occ3, Map.of("identification", List.of()))));

		List<Response> responses = new ArrayList<>();
		for (Phase phase : List.of(Phase.PRE_AMENDMENT, Phase.POST_AMENDMENT)) {
			boolean pre = phase == Phase.PRE_AMENDMENT;
			responses.add(result("occ-1", DATUM, TestType.VALIDATION, phase, pre ? "NOT_COMPLIANT" : "COMPLIANT"));
			responses.add(result("occ-2", DATUM, TestType.VALIDATION, phase, "COMPLIANT"));
			responses.add(result("occ-3", DATUM, TestType.VALIDATION, phase, pre ? "NOT_COMPLIANT" : "COMPLIANT"));
			responses.add(status("occ-1", COUNTRY, phase, "INTERNAL_PREREQUISITES_NOT_MET"));
			responses.add(result("occ-2", COUNTRY, TestType.VALIDATION, phase, "COMPLIANT"));
			responses.add(status("occ-3", COUNTRY, phase, "EXTERNAL_PREREQUISITES_NOT_MET"));
		}
		SubjectRef row1 = new SubjectRef("occ-1", "identification", "identification", "identification", "row-1");
		SubjectRef row2 = new SubjectRef("occ-1", "identification", "identification", "identification", "row-2");
		responses.add(detail("occ-1", NAME, row1, "COMPLIANT", false, List.of()));
		responses.add(detail("occ-1", NAME, row2, "NOT_COMPLIANT", false, List.of()));
		responses.add(detail("occ-1", NAME, null, "NOT_COMPLIANT", true, List.of(row1, row2)));
		responses.add(result("occ-2", NAME, TestType.VALIDATION, Phase.POST_AMENDMENT, "COMPLIANT"));
		responses.add(result("occ-3", NAME, TestType.VALIDATION, Phase.POST_AMENDMENT, "COMPLIANT"));
		responses.add(amendment("occ-1"));
		responses.add(amendment("occ-3"));
		responses.add(new Response("*", UNRESOLVED, TestType.VALIDATION, "", "", Phase.PRE_AMENDMENT, Map.of(),
				OutcomeStatus.UNABLE_TO_RUN, "UNABLE_TO_RUN", "UNABLE_TO_RUN", "No implementation discovered",
				"No implementation discovered", Map.of(), Instant.EPOCH, Instant.EPOCH));

		Map<String, String> labels = new LinkedHashMap<>();
		labels.put(DATUM, "Geodetic datum standard");
		labels.put(COUNTRY, "Country code not empty");
		labels.put(NAME, "Scientific name found");
		labels.put(LOCALITY, "Locality and depth");
		labels.put(DATUM_AMENDMENT, "Geodetic datum standardized");
		labels.put(UNRESOLVED, "Unresolved test");
		ExecutionSummaryMetadata metadata = new ExecutionSummaryMetadata("uc", "Use case", "dataset.zip", 6, 3, 6, 3,
				Map.of(), labels, Map.of(), Map.of());
		List<ImplementationBinding> bindings = List.of(
				binding(DATUM, TestType.VALIDATION, Phase.PRE_AMENDMENT, "dwc:geodeticDatum"),
				binding(COUNTRY, TestType.VALIDATION, Phase.PRE_AMENDMENT, "dwc:countryCode"),
				binding(NAME, TestType.VALIDATION, Phase.POST_AMENDMENT, "dwc:scientificName"),
				binding(LOCALITY, TestType.VALIDATION, Phase.PRE_AMENDMENT, "dwc:locality", "dwc:minimumDepthInMeters"),
				binding(DATUM_AMENDMENT, TestType.AMENDMENT, Phase.AMENDMENT, "dwc:geodeticDatum"),
				qaMeasure("qa-datum", DATUM, "Geodetic datum standard"),
				qaMeasure("qa-country", COUNTRY, "Country code not empty"));
		return new ExecutionSummary(responses, metadata, dataset, bindings);
	}

	private static CanonicalRecord record(String id, int line, Map<String, String> terms) {
		return new CanonicalRecord(id, terms, Map.of(), new SourceRow("occurrence.txt", line));
	}

	private static CanonicalRecord related(String id, int line, String name) {
		return new CanonicalRecord(id, Map.of("scientificName", name), Map.of("scientificName", List.of(
				new SourceCell("identification", "identification", id, "scientificName", "scientificName"))),
				new SourceRow("identification.txt", line));
	}

	private static Response result(String recordId, String testId, TestType type, Phase phase, String result) {
		return new Response(recordId, testId, type, "org.example.Impl", "run", phase, Map.of(), OutcomeStatus.PASSED,
				"RUN_HAS_RESULT", result, result, result, Map.of(), Instant.EPOCH, Instant.EPOCH);
	}

	private static Response status(String recordId, String testId, Phase phase, String status) {
		return new Response(recordId, testId, TestType.VALIDATION, "org.example.Impl", "run", phase, Map.of(),
				OutcomeStatus.FAILED, status, "", status, status, Map.of(), Instant.EPOCH, Instant.EPOCH);
	}

	private static Response detail(String recordId, String testId, SubjectRef subject, String result, boolean derived,
			List<SubjectRef> contributors) {
		return new Response(recordId, testId, TestType.VALIDATION, "org.example.Impl", "run", Phase.POST_AMENDMENT,
				Map.of(), OutcomeStatus.PASSED, "RUN_HAS_RESULT", result, result, result, Map.of(), Instant.EPOCH,
				Instant.EPOCH, subject, derived, contributors);
	}

	private static Response amendment(String recordId) {
		return new Response(recordId, DATUM_AMENDMENT, TestType.AMENDMENT, "org.example.Impl", "amend", Phase.AMENDMENT,
				Map.of(), OutcomeStatus.AMENDED, "AMENDED", "", "standardized", "standardized",
				Map.of("dwc:geodeticDatum", "EPSG:4326"), Instant.EPOCH, Instant.EPOCH);
	}

	private static ImplementationBinding binding(String testId, TestType type, Phase phase, String... terms) {
		List<BoundMethodParameter> parameters = new ArrayList<>();
		for (int index = 0; index < terms.length; index++) {
			MethodParameter parameter = new MethodParameter(index, "p" + index, ParameterRole.ACTED_UPON, terms[index],
					String.class.getName(), true);
			parameters.add(new BoundMethodParameter(parameter, terms[index], null, true, "Mapped"));
		}
		return new ImplementationBinding(testId, type, "org.example.Impl", "run", phase, Map.of(), BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY, "test", true, parameters, List.of());
	}

	private static ImplementationBinding qaMeasure(String testId, String targetTestId, String targetLabel) {
		return new ImplementationBinding(testId, TestType.MEASURE, BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
				BuiltInMeasureSpec.IMPLEMENTATION_METHOD, Phase.POST_AMENDMENT,
				new BuiltInMeasureSpec(BuiltInMeasureSpec.MeasureKind.QA, targetLabel, targetTestId, null,
						List.of("COMPLIANT"), List.of()).asBindingParameters(),
				BindingStatus.BOUND, ParameterizationCapability.DEFAULT_ONLY, "built-in", true, List.of(), List.of());
	}
}
