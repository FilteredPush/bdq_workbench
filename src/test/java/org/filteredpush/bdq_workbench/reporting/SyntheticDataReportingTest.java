package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.SyntheticDataMarkers;
import org.junit.jupiter.api.Test;

/**
 * Tests that every report summarizing the input states whether it carried synthetic, modified,
 * or example data markers.
 */
class SyntheticDataReportingTest {

	private static final SyntheticDataMarkers FOUND = new SyntheticDataMarkers(10, 3, 1, 0, List.of("occ-1", "occ-2"));
	private static final String SUMMARY_LINE = "4 of 10 input record(s) are marked as synthetic or modified example data "
			+ "(3 wholly synthetic, 1 modified from real data)";

	@Test
	void structuredReportsWarnAndListTheMarkers() {
		ExecutionSummary summary = summary(FOUND);

		String html = StructuredHtmlReportExporter.renderHtml(summary);
		String markdown = StructuredMarkdownReportExporter.renderMarkdown(summary);

		assertThat(html).contains("<div class=\"data-warning\" role=\"alert\"><strong>⚠ " + SUMMARY_LINE + ".</strong>");
		assertThat(html).contains("<li><strong>Synthetic or modified example data:</strong> " + SUMMARY_LINE + "</li>");
		assertThat(html.indexOf("data-warning\" role")).isLessThan(html.indexOf("<h2>Run metadata</h2>"));
		assertThat(markdown).contains("> **⚠ " + SUMMARY_LINE + ".**");
		assertThat(markdown).contains("- Synthetic or modified example data: " + SUMMARY_LINE + "\n");
	}

	@Test
	void textSummaryAndRdfReportTheMarkers() throws Exception {
		ExecutionSummary summary = summary(FOUND);

		String text = SummaryReportExporter.renderSummaryText("BDQ summary", summary);
		ByteArrayOutputStream rdf = new ByteArrayOutputStream();
		new RdfResponseExporter(List.of()).export(summary, rdf);
		String turtle = rdf.toString(StandardCharsets.UTF_8);

		assertThat(text).startsWith("BDQ summary\nWARNING: " + SUMMARY_LINE + ".\n");
		assertThat(text).contains("Synthetic or modified example data: " + SUMMARY_LINE + "\n");
		assertThat(turtle).contains("bdqwb:inputSyntheticRecordCount").contains("bdqwb:inputModifiedRecordCount")
				.contains("do not represent actual biodiversity data");
	}

	@Test
	void cleanInputSaysNoneDetectedWithoutAWarning() {
		ExecutionSummary summary = summary(new SyntheticDataMarkers(10, 0, 0, 0, List.of()));

		String html = StructuredHtmlReportExporter.renderHtml(summary);

		assertThat(html).doesNotContain("data-warning\" role");
		assertThat(html).contains("<strong>Synthetic or modified example data:</strong> none detected in 10 input "
				+ "record(s)");
		assertThat(SummaryReportExporter.renderSummaryText("t", summary)).doesNotContain("WARNING");
	}

	private static ExecutionSummary summary(SyntheticDataMarkers markers) {
		RecordDataset dataset = new RecordDataset(List.of(new CanonicalRecord("occ-1", Map.of("occurrenceID", "occ-1"))),
				List.of(), DatasetInputDescription.none().withSyntheticMarkers(markers));
		return new ExecutionSummary(List.of(),
				new ExecutionSummaryMetadata("uc", "Use case", "data.zip", 1, 10, Map.of(), Map.of()), dataset);
	}
}
