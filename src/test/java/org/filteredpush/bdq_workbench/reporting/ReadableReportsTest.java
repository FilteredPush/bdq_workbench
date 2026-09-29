package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests that the text summary and response list name tests and records readably.
 */
class ReadableReportsTest {

	@Test
	void textSummaryHeaderReportsQualityAndExternalPrerequisites() {
		String text = SummaryReportExporter.renderSummaryText("BDQ summary", ReportFixtures.qualityControlRun());

		assertThat(text).contains("NOTE: external prerequisites not met: 2 result(s), in Country code not empty (2)");
		assertThat(text).contains("Records with quality for this use case: 1 of 3 record(s) meet all 2 multi-record QA "
				+ "measure(s) (post-amendment)\n");
		assertThat(text).contains("External prerequisites not met: 2 result(s)");
	}

	@Test
	void responseListLeadsWithRecordAndTestLabels() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new DetailedResponseStreamExporter().export(ReportFixtures.qualityControlRun(), out);
		String tsv = out.toString(StandardCharsets.UTF_8);

		assertThat(tsv).startsWith("recordLabel\ttestLabel\trecordId\tsubjectRef\tsubjectRow\t");
		assertThat(tsv).contains("\nMCZ:Herp:A-1 (occurrence.txt line 2)\tGeodetic datum standard\tocc-1\t");
		assertThat(tsv).contains("\tidentification.txt line 3\t");
	}
}
