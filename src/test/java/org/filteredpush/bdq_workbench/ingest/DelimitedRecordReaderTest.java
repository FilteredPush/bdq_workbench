package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DelimitedRecordReaderTest {

	@Test
	void dwcArchiveParsingToleratesTrailingCharactersAfterQuotedField(@TempDir Path tempDir) throws Exception {
		Path archive = tempDir.resolve("dataset.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
			zip.putNextEntry(new ZipEntry("occurrence.txt"));
			zip.write(("occurrenceID\tcountry\tscientificName\n"
					+ "occ-0\tCanada\t\"Abies\tbalsamea\"\n"
					+ "occ-1\t\"Canada\"x\tAbies balsamea\n"
					+ "occ-2\tMexico\tPinus oocarpa\n").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		RecordDataset dataset = new DwcArchiveIngestor().ingest(archive);

		assertThat(dataset.records()).hasSize(3);
		assertThat(dataset.records().get(0).recordId()).isEqualTo("occ-0");
		assertThat(dataset.records().get(0).terms()).containsEntry("scientificName", "Abies\tbalsamea");
		assertThat(dataset.records().get(1).recordId()).isEqualTo("occ-1");
		assertThat(dataset.records().get(2).terms()).containsEntry("scientificName", "Pinus oocarpa");
	}

	@Test
	void dataPackageParsingToleratesTrailingCharactersAfterQuotedField(@TempDir Path tempDir) throws Exception {
		Path csv = tempDir.resolve("occurrence.csv");
		Files.writeString(
				csv,
				"occurrenceID,country,scientificName\n"
						+ "occ-0,Canada,\"Abies, balsamea\"\n"
						+ "occ-1,\"Canada\"x,Abies balsamea\n"
						+ "occ-2,Mexico,Pinus oocarpa\n",
				StandardCharsets.UTF_8);
		Path manifest = tempDir.resolve("datapackage.json");
		Files.writeString(
				manifest,
				"""
				{
				  "resources": [
				    { "path": "occurrence.csv" }
				  ]
				}
				""",
				StandardCharsets.UTF_8);

		RecordDataset dataset = new DataPackageIngestor().ingest(manifest);

		assertThat(dataset.records()).hasSize(3);
		assertThat(dataset.records().get(0).recordId()).isEqualTo("occ-0");
		assertThat(dataset.records().get(0).terms()).containsEntry("scientificName", "Abies, balsamea");
		assertThat(dataset.records().get(1).recordId()).isEqualTo("occ-1");
		assertThat(dataset.records().get(2).terms()).containsEntry("scientificName", "Pinus oocarpa");
	}
}
