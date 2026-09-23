package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that {@link DwcArchiveIngestor} parses a Darwin Core Archive as its {@code meta.xml}
 * descriptor declares it, rather than assuming a fixed tab-separated, quote-encapsulated layout.
 */
class DwcArchiveMetaIngestTest {

	/** meta.xml for a tab-delimited core declaring no field encapsulation, as most archives do. */
	private static final String UNENCLOSED_META = """
			<?xml version="1.0" encoding="UTF-8"?>
			<archive xmlns="http://rs.tdwg.org/dwc/text/">
			  <core encoding="UTF-8" fieldsTerminatedBy="\\t" linesTerminatedBy="\\n" fieldsEnclosedBy=""
			        ignoreHeaderLines="1" rowType="http://rs.tdwg.org/dwc/terms/Occurrence">
			    <files><location>occurrence.txt</location></files>
			    <id index="0"/>
			    <field index="0" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
			    <field index="1" term="http://rs.tdwg.org/dwc/terms/country"/>
			    <field index="2" term="http://rs.tdwg.org/dwc/terms/verbatimEventDate"/>
			  </core>
			</archive>
			""";

	@Test
	void unenclosedCoreKeepsBareQuoteCharactersAsData(@TempDir Path tempDir) throws Exception {
		Path archive = writeArchive(tempDir, UNENCLOSED_META, "occurrence.txt", StandardCharsets.UTF_8,
				"occurrenceID\tcountry\tverbatimEventDate\n"
						+ "occ-0\tCanada\t1 Jan 1950\n"
						+ "occ-1\tCanada\t\"about\" 6\" of snow, 2 Jan 1950\n"
						+ "occ-2\tMexico\t3 Jan 1950\n");

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(3);
		assertThat(dataset.records().get(1).id()).isEqualTo("occ-1");
		assertThat(dataset.records().get(1).terms())
				.containsEntry("verbatimEventDate", "\"about\" 6\" of snow, 2 Jan 1950");
		assertThat(dataset.records().get(2).terms()).containsEntry("verbatimEventDate", "3 Jan 1950");
	}

	@Test
	void unenclosedCoreDoesNotMergeRecordsAfterALeadingQuote(@TempDir Path tempDir) throws Exception {
		Path archive = writeArchive(tempDir, UNENCLOSED_META, "occurrence.txt", StandardCharsets.UTF_8,
				"occurrenceID\tcountry\tverbatimEventDate\n"
						+ "occ-0\tCanada\t1 Jan 1950\n"
						+ "occ-1\tCanada\t\"ca. 2 Jan 1950\n"
						+ "occ-2\tMexico\t3 Jan 1950\n");

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(3);
		assertThat(dataset.records().get(1).terms()).containsEntry("verbatimEventDate", "\"ca. 2 Jan 1950");
		assertThat(dataset.records().get(2).id()).isEqualTo("occ-2");
	}

	@Test
	void columnNamesComeFromDeclaredTermsNotTheHeaderRow(@TempDir Path tempDir) throws Exception {
		String meta = """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core fieldsTerminatedBy="\\t" fieldsEnclosedBy="" ignoreHeaderLines="1">
				    <files><location>occurrence.txt</location></files>
				    <id index="0"/>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
				    <field index="2" term="http://rs.tdwg.org/dwc/terms/scientificName"/>
				    <field term="http://rs.tdwg.org/dwc/terms/basisOfRecord" default="PreservedSpecimen"/>
				  </core>
				</archive>
				""";
		Path archive = writeArchive(tempDir, meta, "occurrence.txt", StandardCharsets.UTF_8,
				"COL_A\tCOL_B\tCOL_C\n"
						+ "occ-0\tignored\tAbies balsamea\n");

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).terms())
				.containsEntry("occurrenceID", "occ-0")
				.containsEntry("scientificName", "Abies balsamea")
				.containsEntry("basisOfRecord", "PreservedSpecimen")
				.containsEntry("column-1", "ignored")
				.doesNotContainKey("COL_A");
	}

	@Test
	void commaDelimitedQuotedCoreIsParsedAsDeclared(@TempDir Path tempDir) throws Exception {
		String meta = """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core encoding="ISO-8859-1" fieldsTerminatedBy="," fieldsEnclosedBy="&quot;" ignoreHeaderLines="1">
				    <files><location>data/occurrence.csv</location></files>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
				    <field index="1" term="http://rs.tdwg.org/dwc/terms/locality"/>
				  </core>
				</archive>
				""";
		Path archive = writeArchive(tempDir, meta, "data/occurrence.csv", StandardCharsets.ISO_8859_1,
				"occurrenceID,locality\n"
						+ "occ-0,\"Montréal, Québec\"\n");

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).terms()).containsEntry("locality", "Montréal, Québec");
	}

	@Test
	void headerlessCoreIsParsedWhenNoHeaderLinesAreDeclared(@TempDir Path tempDir) throws Exception {
		String meta = """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core fieldsTerminatedBy="\\t" fieldsEnclosedBy="" ignoreHeaderLines="0">
				    <files><location>occurrence.txt</location></files>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
				    <field index="1" term="http://rs.tdwg.org/dwc/terms/country"/>
				  </core>
				</archive>
				""";
		Path archive = writeArchive(tempDir, meta, "occurrence.txt", StandardCharsets.UTF_8,
				"occ-0\tCanada\nocc-1\tMexico\n");

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(2);
		assertThat(dataset.records().get(0).id()).isEqualTo("occ-0");
		assertThat(dataset.records().get(1).terms()).containsEntry("country", "Mexico");
	}

	@Test
	void anEmptyDeclaredIdColumnFallsBackToPositionalRecordIds(@TempDir Path tempDir) throws Exception {
		String meta = """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core fieldsTerminatedBy="\\t" fieldsEnclosedBy="" ignoreHeaderLines="1"
				        rowType="http://rs.tdwg.org/dwc/terms/Occurrence">
				    <files><location>occurrence.txt</location></files>
				    <id index="0"/>
				    <field index="1" term="http://rs.tdwg.org/dwc/terms/institutionCode"/>
				  </core>
				</archive>
				""";
		Path archive = writeArchive(tempDir, meta, "occurrence.txt", StandardCharsets.UTF_8,
				"id\tinstitutionCode\n\tGNHM\n\tGNHM\n");

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(2);
		assertThat(dataset.records()).extracting(record -> record.id()).containsExactly("row-1", "row-2");
	}

	@Test
	void archiveWithoutMetaXmlStillUsesTabDelimitedConvention(@TempDir Path tempDir) throws Exception {
		Path archive = writeArchive(tempDir, null, "occurrence.txt", StandardCharsets.UTF_8,
				"occurrenceID\tcountry\nocc-0\tCanada\n");

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).terms()).containsEntry("country", "Canada");
	}

	@Test
	void metaXmlNamingAMissingCoreFileFallsBackToConvention(@TempDir Path tempDir) throws Exception {
		String meta = """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core fieldsTerminatedBy="\\t" fieldsEnclosedBy="" ignoreHeaderLines="1">
				    <files><location>not-in-archive.txt</location></files>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
				  </core>
				</archive>
				""";
		Path archive = writeArchive(tempDir, meta, "occurrence.txt", StandardCharsets.UTF_8,
				"occurrenceID\tcountry\nocc-0\tCanada\n");

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(1);
		assertThat(dataset.records().get(0).terms()).containsEntry("country", "Canada");
	}

	@Test
	void extensionCoreIdColumnIsParsedFromMetaXml(@TempDir Path tempDir) throws Exception {
		String meta = """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core fieldsTerminatedBy="\\t" fieldsEnclosedBy="" ignoreHeaderLines="1"
				        rowType="http://rs.tdwg.org/dwc/terms/Event">
				    <files><location>event.txt</location></files>
				    <id index="0"/>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/eventID"/>
				  </core>
				  <extension fieldsTerminatedBy="\\t" fieldsEnclosedBy="" ignoreHeaderLines="1"
				             rowType="http://rs.tdwg.org/dwc/terms/Occurrence">
				    <files><location>occurrence.txt</location></files>
				    <coreid index="0"/>
				    <field index="1" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
				  </extension>
				</archive>
				""";
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("meta.xml", meta.getBytes(StandardCharsets.UTF_8));
		entries.put("event.txt", "eventID\nevt-1\n".getBytes(StandardCharsets.UTF_8));
		entries.put("occurrence.txt", "coreid\toccurrenceID\nevt-1\tocc-1\n".getBytes(StandardCharsets.UTF_8));
		Path archive = Files.createTempFile(tempDir, "dataset", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}

		try (ZipFile zipFile = new ZipFile(archive.toFile())) {
			List<CoreTableCandidate<DwcArchiveCoreMeta>> tables = DwcArchiveMetaParser.parseTables(zipFile);
			assertThat(tables).hasSize(2);
			assertThat(tables.get(1).descriptor().coreIdColumn()).isEqualTo("coreid");
		}
	}

	/**
	 * Writes a single-core Darwin Core Archive to a temporary zip file.
	 *
	 * @param tempDir directory to write the archive into
	 * @param metaXml the {@code meta.xml} content, or {@code null} to omit the descriptor
	 * @param coreEntryName the zip entry name of the core data file
	 * @param coreEncoding the encoding to write the core data file in
	 * @param coreContent the core data file content
	 * @return the path of the written archive
	 * @throws Exception if the archive cannot be written
	 */
	private Path writeArchive(Path tempDir, String metaXml, String coreEntryName, Charset coreEncoding,
			String coreContent) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		if (metaXml != null) {
			entries.put("meta.xml", metaXml.getBytes(StandardCharsets.UTF_8));
		}
		entries.put(coreEntryName, coreContent.getBytes(coreEncoding));
		Path archive = Files.createTempFile(tempDir, "dataset", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return archive;
	}
}
