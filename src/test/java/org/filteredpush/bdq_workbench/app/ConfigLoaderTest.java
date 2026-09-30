package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.filteredpush.bdq_workbench.execution.ExecutionPolicy;
import org.filteredpush.bdq_workbench.execution.ResourceClass;
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

	@Test
	void shippedPropertiesProduceTheDefaultExecutionPolicy() {
		ExecutionPolicy policy = new ConfigLoader().load(Map.of("bdq.dataset", "dataset.zip")).executionPolicy();
		ExecutionPolicy defaults = ExecutionPolicy.defaults();

		assertThat(policy.laneSchedulingEnabled()).isTrue();
		assertThat(policy.externalConcurrency()).isEqualTo(2);
		assertThat(policy.unclassifiedConcurrency()).isEqualTo(defaults.unclassifiedConcurrency());
		assertThat(policy.localConcurrency()).isZero();
		assertThat(policy.adaptiveThrottlingEnabled()).isTrue();
		assertThat(policy.circuitFailureThreshold()).isEqualTo(3);
		assertThat(policy.circuitInitialCooldown()).isEqualTo(defaults.circuitInitialCooldown());
		assertThat(policy.circuitMaxCooldown()).isEqualTo(defaults.circuitMaxCooldown());
		assertThat(policy.retriesEnabled()).isTrue();
		assertThat(policy.maxRetries()).isEqualTo(2);
		assertThat(policy.retryBaseDelay()).isEqualTo(Duration.ofMillis(500));
		assertThat(policy.retryMaxDelay()).isEqualTo(Duration.ofSeconds(8));
		assertThat(policy.prePostReuseEnabled()).isTrue();
		assertThat(policy.overrides()).isEmpty();
		assertThat(policy.resourceConcurrency()).isEmpty();
	}

	@Test
	void loadsExecutionPolicySettingsFromOverrides() {
		ExecutionPolicy policy = new ConfigLoader().load(Map.ofEntries(
				Map.entry("bdq.dataset", "dataset.zip"),
				Map.entry("bdq.execution.concurrency.external", "1"),
				Map.entry("bdq.execution.adaptive", "false"),
				Map.entry("bdq.execution.circuit.cooldown.ms", "100"),
				Map.entry("bdq.execution.circuit.cooldown.max.ms", "200"),
				Map.entry("bdq.execution.retries", "0"),
				Map.entry("bdq.execution.reuse", "false"),
				Map.entry("bdq.execution.overrides",
						"urn:uuid:b9c184ce=external,lane:worms,max:1; org.example.Local#validate=local"),
				Map.entry("bdq.execution.lane.limits", "source:worms=1;lane:taxonomy=3"))).executionPolicy();

		assertThat(policy.externalConcurrency()).isEqualTo(1);
		assertThat(policy.adaptiveThrottlingEnabled()).isFalse();
		assertThat(policy.circuitInitialCooldown()).isEqualTo(Duration.ofMillis(100));
		assertThat(policy.circuitMaxCooldown()).isEqualTo(Duration.ofMillis(200));
		assertThat(policy.retriesEnabled()).isFalse();
		assertThat(policy.prePostReuseEnabled()).isFalse();
		assertThat(policy.overrides()).containsExactly(
				Map.entry("urn:uuid:b9c184ce", new ExecutionPolicy.ResourceOverride(ResourceClass.EXTERNAL, "worms", 1)),
				Map.entry("org.example.Local#validate", new ExecutionPolicy.ResourceOverride(ResourceClass.LOCAL, null, null)));
		assertThat(policy.resourceConcurrency()).containsExactly(Map.entry("source:worms", 1), Map.entry("lane:taxonomy", 3));
	}

	@Test
	void rejectsMalformedExecutionSettings() {
		assertThatThrownBy(() -> new ConfigLoader().load(Map.of("bdq.execution.concurrency.external", "many")))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("bdq.execution.concurrency.external");
		assertThatThrownBy(() -> new ConfigLoader().load(Map.of("bdq.execution.concurrency.external", "0")))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("Invalid execution setting");
		assertThatThrownBy(() -> new ConfigLoader().load(Map.of(
				"bdq.execution.retry.delay.ms", "5000", "bdq.execution.retry.delay.max.ms", "10")))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("retryMaxDelay");
		assertThatThrownBy(() -> ConfigLoader.parseResourceOverrides("org.example.Remote=remote"))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("expected one of external, local, unclassified");
		assertThatThrownBy(() -> ConfigLoader.parseResourceOverrides("org.example.Remote=max:0"))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("maxConcurrency");
		assertThatThrownBy(() -> ConfigLoader.parseLaneLimits("source:worms"))
				.isInstanceOf(AppException.class)
				.hasMessageContaining("expected laneKey=N");
	}
}
