package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that {@link DataPackageIngestor} parses a Darwin Core Data Package resource as its
 * manifest's table dialect and schema declare it, rather than assuming default CSV.
 */
class DataPackageDialectIngestTest {

	@Test
	void unquotedDialectKeepsBareQuoteCharactersAsData(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.csv", StandardCharsets.UTF_8,
				"occurrenceID,country,verbatimEventDate\n"
						+ "occ-0,Canada,1 Jan 1950\n"
						+ "occ-1,Canada,\"about\" 2 Jan 1950\n"
						+ "occ-2,Mexico,3 Jan 1950\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    {
				      "path": "occurrence.csv",
				      "dialect": { "delimiter": ",", "quoteChar": "" }
				    }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(3);
		assertThat(dataset.records().get(1).terms()).containsEntry("verbatimEventDate", "\"about\" 2 Jan 1950");
		assertThat(dataset.records().get(2).id()).isEqualTo("occ-2");
	}

	@Test
	void semicolonDelimiterAndDeclaredEncodingAreHonored(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.csv", StandardCharsets.ISO_8859_1,
				"occurrenceID;locality\n"
						+ "occ-0;Montr\u00e9al, Qu\u00e9bec\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    {
				      "path": "occurrence.csv",
				      "encoding": "ISO-8859-1",
				      "dialect": { "delimiter": ";" }
				    }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).terms()).containsEntry("locality", "Montr\u00e9al, Qu\u00e9bec");
	}

	@Test
	void schemaFieldNamesTakePrecedenceOverTheHeaderLine(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.csv", StandardCharsets.UTF_8,
				"COL_A,COL_B\n"
						+ "occ-0,Abies balsamea\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    {
				      "path": "occurrence.csv",
				      "schema": {
				        "fields": [
				          { "name": "occurrenceID" },
				          { "name": "scientificName" }
				        ]
				      }
				    }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).id()).isEqualTo("occ-0");
		assertThat(dataset.records().get(0).terms())
				.containsEntry("scientificName", "Abies balsamea")
				.doesNotContainKey("COL_A");
	}

	@Test
	void headerlessResourceWithSchemaKeepsItsFirstRow(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.csv", StandardCharsets.UTF_8, "occ-0,Canada\nocc-1,Mexico\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    {
				      "path": "occurrence.csv",
				      "dialect": { "header": false },
				      "schema": {
				        "fields": [
				          { "name": "occurrenceID" },
				          { "name": "country" }
				        ]
				      }
				    }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(2);
		assertThat(dataset.records().get(0).id()).isEqualTo("occ-0");
		assertThat(dataset.records().get(1).terms()).containsEntry("country", "Mexico");
	}

	@Test
	void headerlessResourceWithoutSchemaGetsPositionalColumnNames(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.csv", StandardCharsets.UTF_8, "occ-0,Canada\nocc-1,Mexico\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    { "path": "occurrence.csv", "dialect": { "header": false } }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(2);
		assertThat(dataset.records().get(0).terms())
				.containsEntry("column-0", "occ-0")
				.containsEntry("column-1", "Canada");
	}

	@Test
	void dialectGivenByFileReferenceIsResolved(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.tsv", StandardCharsets.UTF_8,
				"occurrenceID\tcountry\nocc-0\tCanada\n");
		writeFile(tempDir, "dialect.json", StandardCharsets.UTF_8,
				"{ \"delimiter\": \"\\t\", \"quoteChar\": \"\" }\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    { "path": "occurrence.tsv", "dialect": "dialect.json" }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).terms()).containsEntry("country", "Canada");
	}

	@Test
	void multipartResourcePathsAreAllRead(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "part-1.csv", StandardCharsets.UTF_8, "occurrenceID,country\nocc-0,Canada\n");
		writeFile(tempDir, "part-2.csv", StandardCharsets.UTF_8, "occurrenceID,country\nocc-1,Mexico\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    { "path": ["part-1.csv", "part-2.csv"] }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(2);
		assertThat(dataset.records().get(0).id()).isEqualTo("occ-0");
		assertThat(dataset.records().get(1).id()).isEqualTo("occ-1");
	}

	@Test
	void escapeCharCommentCharAndNullSequenceAreHonored(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.csv", StandardCharsets.UTF_8,
				"# exported 2026-09-18\n"
						+ "occurrenceID,country,locality\n"
						+ "occ-0,Canada,Montr\\,eal\n"
						+ "occ-1,\\N,Oaxaca\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    {
				      "path": "occurrence.csv",
				      "dialect": {
				        "quoteChar": "",
				        "escapeChar": "\\\\",
				        "commentChar": "#",
				        "nullSequence": "\\\\N"
				      }
				    }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(2);
		assertThat(dataset.records().get(0).terms()).containsEntry("locality", "Montr,eal");
		assertThat(dataset.records().get(1).terms()).containsEntry("country", "");
	}

	@Test
	void resourceWithoutADialectStillParsesAsDefaultCsv(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.csv", StandardCharsets.UTF_8,
				"occurrenceID,country,scientificName\n"
						+ "occ-0,Canada,\"Abies, balsamea\"\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    { "path": "occurrence.csv" }
				  ]
				}
				""");

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).terms()).containsEntry("scientificName", "Abies, balsamea");
	}

	@Test
	void resourcePathEscapingThePackageDirectoryIsRejected(@TempDir Path tempDir) throws Exception {
		Path packageDir = Files.createDirectory(tempDir.resolve("package"));
		writeFile(tempDir, "outside.csv", StandardCharsets.UTF_8, "occurrenceID\nocc-0\n");
		Path manifest = writeManifest(packageDir, """
				{
				  "resources": [
				    { "path": "../outside.csv" }
				  ]
				}
				""");

		assertThatThrownBy(() -> new DataPackageIngestor().ingest(manifest))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("no resource with a readable data file path");
	}

	@Test
	void schemaForeignKeysAreParsed(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.csv", StandardCharsets.UTF_8, "occurrenceID\nocc-1\n");
		writeFile(tempDir, "identification.csv", StandardCharsets.UTF_8, "identificationID,occurrenceID\nid-1,occ-1\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    {
				      "name": "occurrence",
				      "path": "occurrence.csv",
				      "schema": { "fields": [ { "name": "occurrenceID" } ], "primaryKey": "occurrenceID" }
				    },
				    {
				      "name": "identification",
				      "path": "identification.csv",
				      "schema": {
				        "fields": [ { "name": "identificationID" }, { "name": "occurrenceID" } ],
				        "foreignKeys": [
				          { "fields": "occurrenceID", "reference": { "resource": "occurrence", "fields": "occurrenceID" } }
				        ]
				      }
				    }
				  ]
				}
				""");

		var root = new ObjectMapper().readTree(Files.newBufferedReader(manifest));
		List<CoreTableCandidate<DataPackageResourceMeta>> tables =
				DataPackageDialectParser.parseResources(new ObjectMapper(), root, tempDir);

		assertThat(tables).hasSize(2);
		assertThat(tables.get(1).descriptor().foreignKeys())
				.singleElement()
				.satisfies(key -> {
					assertThat(key.field()).isEqualTo("occurrenceID");
					assertThat(key.referenceResource()).isEqualTo("occurrence");
					assertThat(key.referenceField()).isEqualTo("occurrenceID");
				});
	}

	@Test
	void relationalIngestBuildsCoreGraphsFromForeignKeys(@TempDir Path tempDir) throws Exception {
		writeFile(tempDir, "occurrence.csv", StandardCharsets.UTF_8,
				"occurrenceID,eventDate\nocc-1,2020-01-01\n");
		writeFile(tempDir, "identification.csv", StandardCharsets.UTF_8,
				"identificationID,occurrenceID,scientificName\nid-1,occ-1,Abies balsamea\n");
		Path manifest = writeManifest(tempDir, """
				{
				  "resources": [
				    {
				      "name": "occurrence",
				      "path": "occurrence.csv",
				      "schema": {
				        "fields": [ { "name": "occurrenceID" }, { "name": "eventDate" } ],
				        "primaryKey": "occurrenceID"
				      }
				    },
				    {
				      "name": "identification",
				      "path": "identification.csv",
				      "schema": {
				        "fields": [ { "name": "identificationID" }, { "name": "occurrenceID" }, { "name": "scientificName" } ],
				        "foreignKeys": [
				          { "fields": "occurrenceID", "reference": { "resource": "occurrence", "fields": "occurrenceID" } }
				        ]
				      }
				    }
				  ]
				}
				""");

		RelationalIngestResult relational = new RelationalDatasetIngestor().ingest(manifest, "occurrence");

		assertThat(relational.graphs()).hasSize(1);
		assertThat(relational.graphs().get(0).relatedByRelation()).containsKey("identification");
		assertThat(relational.graphs().get(0).relatedByRelation().get("identification")).hasSize(1);
	}

	@Test
	void defaultIngestServiceReadsZippedDataPackageWithMultipleTables(@TempDir Path tempDir) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("datapackage.json", """
				{
				  "resources": [
				    {
				      "name": "occurrence",
				      "path": "occurrence.csv",
				      "schema": {
				        "fields": [ { "name": "occurrenceID" }, { "name": "eventDate" } ],
				        "primaryKey": "occurrenceID"
				      }
				    },
				    {
				      "name": "identification",
				      "path": "identification.csv",
				      "schema": {
				        "fields": [ { "name": "identificationID" }, { "name": "occurrenceID" }, { "name": "scientificName" } ],
				        "foreignKeys": [
				          { "fields": "occurrenceID", "reference": { "resource": "occurrence", "fields": "occurrenceID" } }
				        ]
				      }
				    }
				  ]
				}
				""".getBytes(StandardCharsets.UTF_8));
		entries.put("occurrence.csv", "occurrenceID,eventDate\nocc-1,2020-01-01\n".getBytes(StandardCharsets.UTF_8));
		entries.put("identification.csv",
				"identificationID,occurrenceID,scientificName\nid-1,occ-1,Abies balsamea\n".getBytes(StandardCharsets.UTF_8));
		Path archive = Files.createTempFile(tempDir, "datapackage", ".zip");
		writeZipArchive(archive, entries);

		RecordDataset dataset = new DefaultIngestService().ingest(archive, "");

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).terms()).containsEntry("scientificName", "Abies balsamea");
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
		return writeFile(packageDir, "datapackage.json", StandardCharsets.UTF_8, manifestJson);
	}

	/**
	 * Writes one file of a data package.
	 *
	 * @param packageDir the directory to write the file into
	 * @param fileName the file name
	 * @param encoding the encoding to write the file in
	 * @param content the file content
	 * @return the path of the written file
	 * @throws Exception if the file cannot be written
	 */
	private Path writeFile(Path packageDir, String fileName, Charset encoding, String content) throws Exception {
		Path file = packageDir.resolve(fileName);
		Files.writeString(file, content, encoding);
		return file;
	}

	/**
	 * Writes a zip archive from named byte entries.
	 *
	 * @param archivePath path of the zip file to create
	 * @param entries ordered map of entry names to contents
	 * @throws Exception if the archive cannot be written
	 */
	private void writeZipArchive(Path archivePath, Map<String, byte[]> entries) throws Exception {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archivePath), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
	}
}
