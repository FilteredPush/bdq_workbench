package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests multi-table Darwin Core Archives run without a dataset view.
 */
class DwcArchiveRelationalIngestTest {

	private static final String DWC = "http://rs.tdwg.org/dwc/terms/";

	@Test
	void extensionDoesNotDropCoreColumns(@TempDir Path dir) throws Exception {
		Path archive = zip(dir.resolve("occurrence-with-media.zip"), Map.of(
				"meta.xml", "<archive xmlns=\"http://rs.tdwg.org/dwc/text/\">"
						+ core("Occurrence", "occurrence.txt", "occurrenceID", "basisOfRecord", "countryCode", "scientificName")
						+ extension("http://rs.gbif.org/terms/1.0/Multimedia", "multimedia.txt",
								"http://purl.org/dc/terms/identifier")
						+ "</archive>",
				"occurrence.txt", "id\toccurrenceID\tbasisOfRecord\tcountryCode\tscientificName\n"
						+ "occ-1\tocc-1\tPreservedSpecimen\tCA\tAbies balsamea\n"
						+ "occ-2\tocc-2\tHumanObservation\tUS\tPicea glauca\n",
				"multimedia.txt", "coreid\tidentifier\nocc-1\thttp://img/1\nocc-1\thttp://img/2\n"));

		RecordDataset dataset = new DefaultIngestService().ingest(archive, "", "",
				Map.of("multimedia.txt", org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy.FIRST_ROW));

		assertThat(dataset.records()).extracting(CanonicalRecord::id).containsExactly("occ-1", "occ-2");
		assertThat(dataset.records().get(0).terms())
				.containsEntry("basisOfRecord", "PreservedSpecimen")
				.containsEntry("countryCode", "CA")
				.containsEntry("scientificName", "Abies balsamea")
				.containsEntry("identifier", "http://img/1");
	}

	@Test
	void occurrenceExtensionOfEventCoreArchiveGetsItsEventAsParent(@TempDir Path dir) throws Exception {
		Path archive = zip(dir.resolve("event-core.zip"), Map.of(
				"meta.xml", "<archive xmlns=\"http://rs.tdwg.org/dwc/text/\">"
						+ core("Event", "event.txt", "eventID", "eventDate", "countryCode")
						+ extension(DWC + "Occurrence", "occurrence.txt", DWC + "occurrenceID", DWC + "scientificName")
						+ "</archive>",
				"event.txt", "id\teventID\teventDate\tcountryCode\nEV-1\tEV-1\t2020-06-01\tCA\nEV-2\tEV-2\t2020-07-01\tUS\n",
				"occurrence.txt", "coreid\toccurrenceID\tscientificName\n"
						+ "EV-1\tocc-1\tAbies balsamea\nEV-1\tocc-2\tPicea glauca\nEV-2\tocc-3\tPinus strobus\n"));

		RecordDataset dataset = new DefaultIngestService().ingest(archive, "");

		assertThat(dataset.inputDescription().grainTable()).isEqualTo("occurrence.txt");
		assertThat(dataset.records()).extracting(CanonicalRecord::id).containsExactly("occ-1", "occ-2", "occ-3");
		assertThat(dataset.records()).extracting(record -> record.terms().get("scientificName") + "@"
				+ record.terms().get("eventDate") + "/" + record.terms().get("countryCode"))
				.containsExactly("Abies balsamea@2020-06-01/CA", "Picea glauca@2020-06-01/CA",
						"Pinus strobus@2020-07-01/US");
	}

	private static String core(String rowType, String file, String... terms) {
		StringBuilder xml = new StringBuilder("<core encoding=\"UTF-8\" fieldsTerminatedBy=\"\\t\" linesTerminatedBy=\"\\n\" "
				+ "fieldsEnclosedBy=\"\" ignoreHeaderLines=\"1\" rowType=\"" + DWC + rowType + "\"><files><location>" + file
				+ "</location></files><id index=\"0\"/>");
		for (int index = 0; index < terms.length; index++) {
			xml.append("<field index=\"").append(index + 1).append("\" term=\"").append(DWC).append(terms[index]).append("\"/>");
		}
		return xml.append("</core>").toString();
	}

	private static String extension(String rowType, String file, String... termIris) {
		StringBuilder xml = new StringBuilder("<extension encoding=\"UTF-8\" fieldsTerminatedBy=\"\\t\" "
				+ "linesTerminatedBy=\"\\n\" fieldsEnclosedBy=\"\" ignoreHeaderLines=\"1\" rowType=\"" + rowType
				+ "\"><files><location>" + file + "</location></files><coreid index=\"0\"/>");
		for (int index = 0; index < termIris.length; index++) {
			xml.append("<field index=\"").append(index + 1).append("\" term=\"").append(termIris[index]).append("\"/>");
		}
		return xml.append("</extension>").toString();
	}

	private static Path zip(Path archive, Map<String, String> entries) throws Exception {
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, String> entry : new java.util.TreeMap<>(entries).entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		return archive;
	}
}
