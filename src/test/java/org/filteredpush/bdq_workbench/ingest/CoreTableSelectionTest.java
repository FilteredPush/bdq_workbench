package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that the ingestors choose which of a dataset's tables to run against from the dataset's
 * own metadata, rather than taking the declared core or the first resource unconditionally.
 */
class CoreTableSelectionTest {

	@Test
	void eventCoreArchivePrefersItsOccurrenceExtension(@TempDir Path tempDir) throws Exception {
		Path archive = writeArchive(tempDir, """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core rowType="http://rs.tdwg.org/dwc/terms/Event" fieldsTerminatedBy="\\t"
				        fieldsEnclosedBy="" ignoreHeaderLines="1">
				    <files><location>event.txt</location></files>
				    <id index="0"/>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/eventID"/>
				    <field index="1" term="http://rs.tdwg.org/dwc/terms/eventDate"/>
				  </core>
				  <extension rowType="http://rs.tdwg.org/dwc/terms/Occurrence" fieldsTerminatedBy="\\t"
				             fieldsEnclosedBy="" ignoreHeaderLines="1">
				    <files><location>occurrence.txt</location></files>
				    <coreid index="0"/>
				    <field index="1" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
				    <field index="2" term="http://rs.tdwg.org/dwc/terms/scientificName"/>
				  </extension>
				</archive>
				""",
				Map.of(
						"event.txt", "eventID\teventDate\nev-0\t1950-01-01\n",
						"occurrence.txt", "coreid\toccurrenceID\tscientificName\n"
								+ "ev-0\tocc-0\tAbies balsamea\n"
								+ "ev-0\tocc-1\tPinus strobus\n"));

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(2);
		assertThat(dataset.records().get(0).id()).isEqualTo("occ-0");
		assertThat(dataset.records().get(0).terms()).containsEntry("scientificName", "Abies balsamea");
	}

	@Test
	void occurrenceCoreArchiveKeepsItsDeclaredCore(@TempDir Path tempDir) throws Exception {
		Path archive = writeArchive(tempDir, """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core rowType="http://rs.tdwg.org/dwc/terms/Occurrence" fieldsTerminatedBy="\\t"
				        fieldsEnclosedBy="" ignoreHeaderLines="1">
				    <files><location>occurrence.txt</location></files>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
				  </core>
				  <extension rowType="http://rs.gbif.org/terms/1.0/Multimedia" fieldsTerminatedBy="\\t"
				             fieldsEnclosedBy="" ignoreHeaderLines="1">
				    <files><location>multimedia.txt</location></files>
				    <field index="1" term="http://purl.org/dc/terms/identifier"/>
				  </extension>
				</archive>
				""",
				Map.of(
						"occurrence.txt", "occurrenceID\nocc-0\n",
						"multimedia.txt", "coreid\tidentifier\nocc-0\thttps://example.org/a.jpg\n"));

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).id()).isEqualTo("occ-0");
	}

	@Test
	void requestedArchiveTableOverridesTheRanking(@TempDir Path tempDir) throws Exception {
		Path archive = writeArchive(tempDir, """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core rowType="http://rs.tdwg.org/dwc/terms/Event" fieldsTerminatedBy="\\t"
				        fieldsEnclosedBy="" ignoreHeaderLines="1">
				    <files><location>event.txt</location></files>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/eventID"/>
				  </core>
				  <extension rowType="http://rs.tdwg.org/dwc/terms/Occurrence" fieldsTerminatedBy="\\t"
				             fieldsEnclosedBy="" ignoreHeaderLines="1">
				    <files><location>occurrence.txt</location></files>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
				  </extension>
				</archive>
				""",
				Map.of(
						"event.txt", "eventID\nev-0\n",
						"occurrence.txt", "occurrenceID\nocc-0\n"));

		assertThat(new DwcArchiveIngestor().ingest(archive, "event.txt").records().get(0).id())
				.isEqualTo("ev-0");
		assertThat(new DwcArchiveIngestor().ingest(archive, "Event").records().get(0).id())
				.isEqualTo("ev-0");
		assertThat(new DwcArchiveIngestor().ingest(archive, "no-such-table").records().get(0).id())
				.isEqualTo("occ-0");
	}

	@Test
	void dataPackagePicksTheOccurrenceResourceNotTheFirstOne(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "agent.csv", "agentID,agentType\nag-0,person\n");
		writeFile(tempDir, "event.csv", "eventID,eventDate\nev-0,1950-01-01\n");
		writeFile(tempDir, "occurrence.csv", "occurrenceID,basisOfRecord\nocc-0,PreservedSpecimen\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    { "name": "agent", "path": "agent.csv" },
				    { "name": "event", "path": "event.csv" },
				    { "name": "occurrence", "path": "occurrence.csv" }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).id()).isEqualTo("occ-0");
	}

	@Test
	void dataPackageIdentifiesATableByItsSchemaReference(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "table-a.csv", "eventID,eventDate\nev-0,1950-01-01\n");
		writeFile(tempDir, "table-b.csv", "occurrenceID,basisOfRecord\nocc-0,PreservedSpecimen\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    { "name": "a", "path": "table-a.csv",
				      "schema": "https://example.org/dwc-dp/event-table-schema.json" },
				    { "name": "b", "path": "table-b.csv",
				      "schema": "https://example.org/dwc-dp/occurrence-table-schema.json" }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records().get(0).id()).isEqualTo("occ-0");
	}

	@Test
	void dataPackageFallsBackToInferringRowTypeFromSchemaFieldNames(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "t1.csv", "a,b\n1,2\n");
		writeFile(tempDir, "t2.csv", "occurrenceID,basisOfRecord,occurrenceStatus\nocc-0,PreservedSpecimen,present\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    {
				      "name": "t1", "path": "t1.csv",
				      "schema": { "fields": [ { "name": "a" }, { "name": "b" } ] }
				    },
				    {
				      "name": "t2", "path": "t2.csv",
				      "schema": {
				        "fields": [
				          { "name": "occurrenceID" },
				          { "name": "basisOfRecord" },
				          { "name": "occurrenceStatus" }
				        ]
				      }
				    }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records().get(0).id()).isEqualTo("occ-0");
	}

	@Test
	void requestedDataPackageResourceOverridesTheRanking(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "event.csv", "eventID\nev-0\n");
		writeFile(tempDir, "occurrence.csv", "occurrenceID\nocc-0\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    { "name": "event", "path": "event.csv" },
				    { "name": "occurrence", "path": "occurrence.csv" }
				  ]
				}
				""");

		assertThat(new DataPackageIngestor().ingest(manifest, "event").records().get(0).id()).isEqualTo("ev-0");
		assertThat(new DataPackageIngestor().ingest(manifest, "event.csv").records().get(0).id()).isEqualTo("ev-0");
		assertThat(new DataPackageIngestor().ingest(manifest).records().get(0).id()).isEqualTo("occ-0");
	}

	@Test
	void unidentifiableDataPackageResourcesKeepDeclarationOrder(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "first.csv", "a,b\nx,y\n");
		writeFile(tempDir, "second.csv", "c,d\nz,w\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    { "name": "first", "path": "first.csv" },
				    { "name": "second", "path": "second.csv" }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records().get(0).terms()).containsEntry("a", "x");
	}

	@Test
	void rowTypeIsNotInferredFromASingleIncidentalTerm() {
		assertThat(DatasetRowType.fromColumnNames(java.util.List.of("occurrenceID", "someLocalField")))
				.isEqualTo(DatasetRowType.OTHER);
		assertThat(DatasetRowType.fromColumnNames(java.util.List.of("occurrenceID", "basisOfRecord", "taxonID")))
				.isEqualTo(DatasetRowType.OCCURRENCE);
		assertThat(DatasetRowType.fromIdentifier("http://rs.gbif.org/terms/1.0/Multimedia"))
				.isEqualTo(DatasetRowType.OTHER);
		assertThat(DatasetRowType.fromIdentifier("occurrence-table-schema.json"))
				.isEqualTo(DatasetRowType.OCCURRENCE);
	}

	/**
	 * Writes a Darwin Core Archive containing a {@code meta.xml} and the given data files.
	 *
	 * @param tempDir directory to write the archive into
	 * @param metaXml the {@code meta.xml} content
	 * @param dataFiles data file contents by zip entry name
	 * @return the path of the written archive
	 * @throws Exception if the archive cannot be written
	 */
	private Path writeArchive(Path tempDir, String metaXml, Map<String, String> dataFiles) throws Exception {
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("meta.xml", metaXml);
		entries.putAll(dataFiles);
		Path archive = Files.createTempFile(tempDir, "dataset", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return archive;
	}

	/**
	 * Writes a {@code datapackage.json} manifest into a directory.
	 *
	 * @param packageDir the directory to write the manifest into
	 * @param manifestJson the manifest content
	 * @return the path of the written manifest
	 * @throws Exception if the manifest cannot be written
	 */
	private Path writeManifest(Path packageDir, String manifestJson) throws Exception {
		return writeFile(packageDir, "datapackage.json", manifestJson);
	}

	/**
	 * Writes one UTF-8 file of a data package.
	 *
	 * @param packageDir the directory to write the file into
	 * @param fileName the file name
	 * @param content the file content
	 * @return the path of the written file
	 * @throws Exception if the file cannot be written
	 */
	private Path writeFile(Path packageDir, String fileName, String content) throws Exception {
		Path file = packageDir.resolve(fileName);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}
}
