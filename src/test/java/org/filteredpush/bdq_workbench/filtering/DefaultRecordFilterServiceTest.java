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
}
