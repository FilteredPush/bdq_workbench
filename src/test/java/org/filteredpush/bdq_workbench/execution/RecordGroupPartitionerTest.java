package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.junit.jupiter.api.Test;

class RecordGroupPartitionerTest {

    @Test
    void groupsRecordsSharingIdenticalValuesForTheGivenFields() {
        List<CanonicalRecord> records = List.of(
                new CanonicalRecord("r1", Map.of("country", "Greenland")),
                new CanonicalRecord("r2", Map.of("country", "Greenland")),
                new CanonicalRecord("r3", Map.of("country", "Denmark")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country"));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).representative().id()).isEqualTo("r1");
        assertThat(groups.get(0).memberRecordIds()).containsExactly("r1", "r2");
        assertThat(groups.get(1).representative().id()).isEqualTo("r3");
        assertThat(groups.get(1).memberRecordIds()).containsExactly("r3");
    }

    @Test
    void groupsMultipleFieldsAsACombinedKey() {
        List<CanonicalRecord> records = List.of(
                new CanonicalRecord("r1", Map.of("country", "Greenland", "stateProvince", "A")),
                new CanonicalRecord("r2", Map.of("country", "Greenland", "stateProvince", "B")),
                new CanonicalRecord("r3", Map.of("country", "Greenland", "stateProvince", "A")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country", "stateProvince"));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).memberRecordIds()).containsExactly("r1", "r3");
        assertThat(groups.get(1).memberRecordIds()).containsExactly("r2");
    }

    @Test
    void recordsMissingTheSameFieldGroupTogether() {
        List<CanonicalRecord> records = List.of(
                new CanonicalRecord("r1", Map.of("otherField", "x")),
                new CanonicalRecord("r2", Map.of("otherField", "y")),
                new CanonicalRecord("r3", Map.of("country", "Greenland", "otherField", "z")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country"));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).memberRecordIds()).containsExactly("r1", "r2");
        assertThat(groups.get(1).memberRecordIds()).containsExactly("r3");
    }

    @Test
    void groupingIsExactMatchWithNoCaseNormalization() {
        List<CanonicalRecord> records = List.of(
                new CanonicalRecord("r1", Map.of("country", "Greenland")),
                new CanonicalRecord("r2", Map.of("country", "greenland")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country"));

        assertThat(groups).hasSize(2);
    }

    @Test
    void emptyFieldListGroupsEveryRecordTogether() {
        List<CanonicalRecord> records = List.of(
                new CanonicalRecord("r1", Map.of("country", "Greenland")),
                new CanonicalRecord("r2", Map.of("country", "Denmark")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of());

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).memberRecordIds()).containsExactly("r1", "r2");
    }

    @Test
    void singleRecordProducesASingletonGroup() {
        List<CanonicalRecord> records = List.of(new CanonicalRecord("r1", Map.of("country", "Greenland")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country"));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).representative().id()).isEqualTo("r1");
        assertThat(groups.get(0).memberRecordIds()).containsExactly("r1");
    }

    @Test
    void emptyRecordListProducesNoGroups() {
        List<RecordGroup> groups = RecordGroupPartitioner.partition(List.of(), List.of("country"));

        assertThat(groups).isEmpty();
    }
}
