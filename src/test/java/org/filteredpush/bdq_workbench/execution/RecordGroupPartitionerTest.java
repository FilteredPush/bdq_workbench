package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.EvaluationSubject;
import org.junit.jupiter.api.Test;

class RecordGroupPartitionerTest {

    @Test
    void groupsRecordsSharingIdenticalValuesForTheGivenFields() {
        List<EvaluationSubject> records = List.of(
                subject("r1", Map.of("country", "Greenland")),
                subject("r2", Map.of("country", "Greenland")),
                subject("r3", Map.of("country", "Denmark")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country"));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).representative().effectiveRecord().id()).isEqualTo("r1");
        assertThat(groups.get(0).members()).extracting(EvaluationSubject::coreRecordId).containsExactly("r1", "r2");
        assertThat(groups.get(1).representative().effectiveRecord().id()).isEqualTo("r3");
        assertThat(groups.get(1).members()).extracting(EvaluationSubject::coreRecordId).containsExactly("r3");
    }

    @Test
    void groupsMultipleFieldsAsACombinedKey() {
        List<EvaluationSubject> records = List.of(
                subject("r1", Map.of("country", "Greenland", "stateProvince", "A")),
                subject("r2", Map.of("country", "Greenland", "stateProvince", "B")),
                subject("r3", Map.of("country", "Greenland", "stateProvince", "A")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country", "stateProvince"));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).members()).extracting(EvaluationSubject::coreRecordId).containsExactly("r1", "r3");
        assertThat(groups.get(1).members()).extracting(EvaluationSubject::coreRecordId).containsExactly("r2");
    }

    @Test
    void recordsMissingTheSameFieldGroupTogether() {
        List<EvaluationSubject> records = List.of(
                subject("r1", Map.of("otherField", "x")),
                subject("r2", Map.of("otherField", "y")),
                subject("r3", Map.of("country", "Greenland", "otherField", "z")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country"));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).members()).extracting(EvaluationSubject::coreRecordId).containsExactly("r1", "r2");
        assertThat(groups.get(1).members()).extracting(EvaluationSubject::coreRecordId).containsExactly("r3");
    }

    @Test
    void groupingIsExactMatchWithNoCaseNormalization() {
        List<EvaluationSubject> records = List.of(
                subject("r1", Map.of("country", "Greenland")),
                subject("r2", Map.of("country", "greenland")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country"));

        assertThat(groups).hasSize(2);
    }

    @Test
    void emptyFieldListGroupsEveryRecordTogether() {
        List<EvaluationSubject> records = List.of(
                subject("r1", Map.of("country", "Greenland")),
                subject("r2", Map.of("country", "Denmark")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of());

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).members()).extracting(EvaluationSubject::coreRecordId).containsExactly("r1", "r2");
    }

    @Test
    void singleRecordProducesASingletonGroup() {
        List<EvaluationSubject> records = List.of(subject("r1", Map.of("country", "Greenland")));

        List<RecordGroup> groups = RecordGroupPartitioner.partition(records, List.of("country"));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).representative().effectiveRecord().id()).isEqualTo("r1");
        assertThat(groups.get(0).members()).extracting(EvaluationSubject::coreRecordId).containsExactly("r1");
    }

    @Test
    void emptyRecordListProducesNoGroups() {
        List<RecordGroup> groups = RecordGroupPartitioner.partition(List.of(), List.of("country"));

        assertThat(groups).isEmpty();
    }

    private static EvaluationSubject subject(String id, Map<String, String> terms) {
        return EvaluationSubject.flat(new CanonicalRecord(id, terms));
    }
}
