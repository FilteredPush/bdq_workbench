package org.filteredpush.bdq_workbench.filtering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordFilterSpec;
import org.filteredpush.bdq_workbench.model.RecordFilterSummary;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.SourceCell;
import org.junit.jupiter.api.Test;

class DefaultRecordFilterServiceTest {

	@Test
	void filtersRecordsUsingAndAcrossFieldsAndOrWithinAField() {
		RecordDataset dataset = new RecordDataset(List.of(
				new CanonicalRecord("r1", Map.of("genus", "Abies", "dwc:country", "Canada")),
				new CanonicalRecord("r2", Map.of("genus", "Pinus", "dwc:country", "Canada")),
				new CanonicalRecord("r3", Map.of("genus", "Abies", "dwc:country", "Mexico"))));

		RecordFilterSummary summary = new DefaultRecordFilterService().apply(
				dataset,
				RecordFilterSpec.parse("dwc:genus=Abies|Pinus;country=Canada"));

		assertThat(summary.filteredDataset().records()).extracting(CanonicalRecord::id).containsExactly("r1", "r2");
		assertThat(summary.originalRecordCount()).isEqualTo(3);
		assertThat(summary.filteredRecordCount()).isEqualTo(2);
		assertThat(summary.excludedRecordCount()).isEqualTo(1);
		assertThat(summary.resolvedCriteria()).containsEntry("genus", List.of("Abies", "Pinus"));
		assertThat(summary.resolvedCriteria()).containsEntry("dwc:country", List.of("Canada"));
	}

	@Test
	void rejectsUnknownFilterFields() {
		RecordDataset dataset = new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:country", "Canada"))));

		assertThatThrownBy(() -> new DefaultRecordFilterService().apply(dataset, RecordFilterSpec.parse("family=Pinaceae")))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("Unknown record filter field: family");
	}

	@Test
	void rejectsAmbiguousFilterFields() {
		RecordDataset dataset = new RecordDataset(List.of(new CanonicalRecord(
				"r1",
				Map.of("country", "Canada", "dwc:country", "Canada"))));

		assertThatThrownBy(() -> new DefaultRecordFilterService().apply(dataset, RecordFilterSpec.parse("country=Canada")))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("Ambiguous record filter field: country");
	}

	@Test
	void preservesStructuredGraphsForKeptCoreRecords() {
		CanonicalRecord core1 = record("r1", "occurrence", Map.of("country", "Canada"));
		CanonicalRecord core2 = record("r2", "occurrence", Map.of("country", "Mexico"));
		RecordDataset dataset = new RecordDataset(
				List.of(core1, core2),
				List.of(
						new RecordGraph(core1, Map.of("identification", List.of(
								record("id1", "identification", Map.of("scientificName", "Aus bus"))))),
						new RecordGraph(core2, Map.of("identification", List.of(
								record("id2", "identification", Map.of("scientificName", "Cus dus")))))));

		RecordFilterSummary summary = new DefaultRecordFilterService().apply(dataset, RecordFilterSpec.parse("country=Canada"));

		assertThat(summary.filteredDataset().records()).extracting(CanonicalRecord::id).containsExactly("r1");
		assertThat(summary.filteredDataset().recordGraphs()).singleElement().satisfies(graph -> {
			assertThat(graph.core().id()).isEqualTo("r1");
			assertThat(graph.relatedByRelation().get("identification")).singleElement()
					.extracting(CanonicalRecord::id)
					.isEqualTo("id1");
		});
	}

	private static CanonicalRecord record(String id, String table, Map<String, String> terms) {
		Map<String, List<SourceCell>> provenance = new java.util.LinkedHashMap<>();
		terms.keySet().forEach(term -> provenance.put(term, List.of(new SourceCell(table, table, id, term, term))));
		return new CanonicalRecord(id, terms, provenance);
	}
}
