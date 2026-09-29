package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

	@Test
	void loadsRecordFilterConfigurationFromOverrides() {
		AppConfig config = new ConfigLoader().load(Map.of(
				"bdq.dataset", "dataset.zip",
				"bdq.record.filters", "dwc:genus=Abies|Pinus; country=Canada"));

		assertThat(config.recordFilter().criteria())
				.containsEntry("dwc:genus", java.util.List.of("Abies", "Pinus"))
				.containsEntry("country", java.util.List.of("Canada"));
	}

	@Test
	void rejectsBlankRecordFilterValues() {
		assertThatThrownBy(() -> new ConfigLoader().load(Map.of(
				"bdq.dataset", "dataset.zip",
				"bdq.record.filters", "dwc:country=Canada| ")))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("values must not be blank");
	}

	@Test
	void loadsDatasetViewPathFromOverrides() {
		AppConfig config = new ConfigLoader().load(Map.of(
				"bdq.dataset", "dataset.zip",
				"bdq.dataset.view", "/tmp/view.json"));

		assertThat(config.datasetView()).isEqualTo("/tmp/view.json");
	}

	@Test
	void loadsJoinPoliciesFromOverrides() {
		AppConfig config = new ConfigLoader().load(Map.of(
				"bdq.dataset", "dataset.zip",
				"bdq.dataset.join.policies", "identification=expand, multimedia.txt=FIRST_ROW"));

		assertThat(config.datasetJoinPolicies()).containsExactly(
				Map.entry("identification", DatasetViewCardinalityPolicy.EXPAND),
				Map.entry("multimedia.txt", DatasetViewCardinalityPolicy.FIRST_ROW));
	}

	@Test
	void rejectsMalformedJoinPolicies() {
		assertThatThrownBy(() -> ConfigLoader.parseJoinPolicies("identification"))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("expected table=POLICY");
		assertThatThrownBy(() -> ConfigLoader.parseJoinPolicies("identification=EVERY_ROW"))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("Invalid join policy 'EVERY_ROW' for table identification");
	}
}
