package org.filteredpush.bdq_workbench.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetSchemaInspectorTest {

	@Test
	void dwcaWithOneDeclaredCoreReportsSingleTable(@TempDir Path tempDir) throws Exception {
		String meta = """
				<?xml version="1.0" encoding="UTF-8"?>
				<archive xmlns="http://rs.tdwg.org/dwc/text/">
				  <core fieldsTerminatedBy="\\t" fieldsEnclosedBy="" ignoreHeaderLines="1"
				        rowType="http://rs.tdwg.org/dwc/terms/Occurrence">
				    <files><location>occurrence.txt</location></files>
				    <id index="0"/>
				    <field index="0" term="http://rs.tdwg.org/dwc/terms/occurrenceID"/>
				  </core>
				</archive>
				""";
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("meta.xml", meta.getBytes(StandardCharsets.UTF_8));
		entries.put("occurrence.txt", "occurrenceID\nocc-1\n".getBytes(StandardCharsets.UTF_8));
		Path archive = Files.createTempFile(tempDir, "dataset", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}

		DatasetSchemaInspector.DatasetSchemaOverview overview = new DatasetSchemaInspector().inspect(archive);

		assertThat(overview.tables()).hasSize(1);
		assertThat(overview.describeTables()).contains("Dataset offers one table, occurrence.txt");
		assertThat(overview.tables().get(0).describe()).contains("declared core");
	}

	@Test
	void datapackageWithTwoResourcesReportsTwoTables(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("occurrence.csv"), "occurrenceID\nocc-1\n", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("event.csv"), "eventID\nevt-1\n", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("datapackage.json"), """
				{
				  "resources": [
				    { "name": "occurrence", "path": "occurrence.csv" },
				    { "name": "event", "path": "event.csv" }
				  ]
				}
				""", StandardCharsets.UTF_8);

		DatasetSchemaInspector.DatasetSchemaOverview overview =
				new DatasetSchemaInspector().inspect(tempDir.resolve("datapackage.json"));

		assertThat(overview.tables()).hasSize(2);
		assertThat(overview.describeTables()).isEqualTo("Dataset offers 2 tables");
	}
}
