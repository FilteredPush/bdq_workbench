package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.ingest.DatasetViewIO;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.ingest.RelationalDatasetIngestor;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.reporting.InputViewOverview.TableRole;
import org.filteredpush.bdq_workbench.reporting.InputViewOverview.TableOverview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that records are named by identifying terms from a related table no test reads — the
 * material rows that carry a Darwin Core Data Package occurrence's institution code, collection
 * code and catalog number — even when the view does not map them, and that such a table is
 * reported as identifying records rather than as ignored.
 */
class RecordIdentificationReportTest {

	@Test
	void occurrencesAreNamedFromTheirMaterialRowsAndMaterialIsNotIgnored(@TempDir Path dir) throws Exception {
		ExecutionSummary summary = summary(dataset(dir));
		ReportDigest digest = ReportDigest.from(summary);

		assertThat(digest.recordLabel("occ-1")).isEqualTo("MCZ:Orn:1 (occurrence.csv line 2)");
		/* Several material rows: their distinct catalog numbers are all given. */
		assertThat(digest.recordLabel("occ-2")).isEqualTo("MCZ:Orn:2 | 3 (occurrence.csv line 3)");
		/* No material rows, so no catalog number: the record is named by its occurrenceID. */
		assertThat(digest.recordLabel("occ-3")).isEqualTo("occurrenceID occ-3 (occurrence.csv line 4)");
		/* Material with codes but no catalog number (as in tracking data): codes plus occurrenceID. */
		assertThat(digest.recordLabel("occ-4"))
				.isEqualTo("example.org:Modified Example occurrenceID occ-4 (occurrence.csv line 5)");

		InputViewOverview overview = InputViewOverview.from(summary);
		assertThat(overview.tables()).extracting(TableOverview::name, TableOverview::role)
				.contains(org.assertj.core.api.Assertions.tuple("material", TableRole.IDENTIFYING),
						org.assertj.core.api.Assertions.tuple("audit", TableRole.IGNORED_NO_BINDINGS));
		String html = StructuredHtmlReportExporter.renderHtml(summary);
		assertThat(html).contains("<strong>Used only to identify records</strong> (no test reads them; the reports "
				+ "name records by these terms): <code>material</code> (institutionCode, collectionCode, catalogNumber)");
		assertThat(html).contains(">names records: institutionCode, collectionCode, catalogNumber</text>")
				.contains(">records named from: material</text>");
		assertThat(StructuredMarkdownReportExporter.renderMarkdown(summary))
				.contains("1 used only to identify records, ")
				.contains("Used only to identify records (no test reads them; the reports name records by these "
						+ "terms): `material` (institutionCode, collectionCode, catalogNumber)");
	}

	/**
	 * Writes an occurrence / material / audit data package and ingests it through a view that maps
	 * only occurrence terms.
	 *
	 * @param dir the directory to write the package into
	 * @return the ingested dataset
	 * @throws Exception if the package cannot be written
	 */
	private static RecordDataset dataset(Path dir) throws Exception {
		Files.writeString(dir.resolve("occurrence.csv"),
				"occurrenceID,scientificName\nocc-1,Aves\nocc-2,Aves\nocc-3,Aves\nocc-4,Aves\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("material.csv"),
				"materialEntityID,evidenceForOccurrenceID,institutionCode,collectionCode,catalogNumber\n"
						+ "m-1,occ-1,MCZ,Orn,1\nm-2,occ-2,MCZ,Orn,2\nm-3,occ-2,MCZ,Orn,3\nm-4,occ-4,example.org,Modified Example,\n",
				StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("audit.csv"), "auditID,note\na-1,checked\n", StandardCharsets.UTF_8);
		Path manifest = Files.writeString(dir.resolve("datapackage.json"), """
				{
				  "resources": [
				    { "name": "occurrence", "path": "occurrence.csv",
				      "schema": { "fields": [ { "name": "occurrenceID" }, { "name": "scientificName" } ],
				                  "primaryKey": "occurrenceID" } },
				    { "name": "material", "path": "material.csv",
				      "schema": { "fields": [ { "name": "materialEntityID" }, { "name": "evidenceForOccurrenceID" },
				                              { "name": "institutionCode" }, { "name": "collectionCode" },
				                              { "name": "catalogNumber" } ],
				                  "primaryKey": "materialEntityID",
				                  "foreignKeys": [ { "fields": "evidenceForOccurrenceID",
				                                     "reference": { "resource": "occurrence", "fields": "occurrenceID" } } ] } },
				    { "name": "audit", "path": "audit.csv",
				      "schema": { "fields": [ { "name": "auditID" }, { "name": "note" } ], "primaryKey": "auditID" } }
				  ]
				}
				""", StandardCharsets.UTF_8);
		String fingerprint = new RelationalDatasetIngestor().ingest(manifest, "occurrence").schema().schemaFingerprint();
		DatasetView view = new DatasetView("occurrence", fingerprint, List.of(), List.of(
				new DatasetViewMapping("occurrenceID", "occurrence", "occurrenceID"),
				new DatasetViewMapping("scientificName", "occurrence", "scientificName")));
		Path viewPath = dir.resolve("view.json");
		new DatasetViewIO().save(viewPath, view);
		return new DefaultIngestService().ingest(manifest, "occurrence", viewPath.toString());
	}

	/**
	 * Wraps a dataset in a summary with one binding reading {@code scientificName}.
	 *
	 * @param dataset the dataset
	 * @return the summary
	 */
	private static ExecutionSummary summary(RecordDataset dataset) {
		MethodParameter actedUpon = new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, "dwc:scientificName",
				String.class.getName(), true);
		ImplementationBinding binding = new ImplementationBinding("urn:test:name", TestType.VALIDATION,
				"org.example.Impl", "validate", Phase.PRE_AMENDMENT, Map.of(), BindingStatus.BOUND,
				ParameterizationCapability.DEFAULT_ONLY, "default", true,
				List.of(new BoundMethodParameter(actedUpon, "scientificName", null, true, "Mapped")), List.of());
		return new ExecutionSummary(List.of(), null, dataset, List.of(binding));
	}
}
