package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
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
}
