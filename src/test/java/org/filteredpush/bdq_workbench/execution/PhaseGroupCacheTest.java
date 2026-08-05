package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.junit.jupiter.api.Test;

class PhaseGroupCacheTest {

    @Test
    void sharesOnePartitionAcrossRequestsForTheSameFieldSet() {
        List<CanonicalRecord> records = List.of(
                new CanonicalRecord("r1", Map.of("country", "Greenland")),
                new CanonicalRecord("r2", Map.of("country", "Greenland")),
                new CanonicalRecord("r3", Map.of("country", "Denmark")));
        PhaseGroupCache cache = new PhaseGroupCache(records);

        List<RecordGroup> first = cache.groupsFor(List.of("country"));
        List<RecordGroup> second = cache.groupsFor(List.of("country"));

        assertThat(second).isSameAs(first);
        assertThat(first).hasSize(2);
    }

    @Test
    void computesIndependentPartitionsForDifferentFieldSets() {
        List<CanonicalRecord> records = List.of(
                new CanonicalRecord("r1", Map.of("country", "Greenland", "stateProvince", "A")),
                new CanonicalRecord("r2", Map.of("country", "Greenland", "stateProvince", "B")));
        PhaseGroupCache cache = new PhaseGroupCache(records);

        List<RecordGroup> byCountry = cache.groupsFor(List.of("country"));
        List<RecordGroup> byState = cache.groupsFor(List.of("stateProvince"));

        assertThat(byCountry).hasSize(1);
        assertThat(byState).hasSize(2);
    }

    @Test
    void invalidateDiscardsOnlyPartitionsTouchingChangedFields() {
        List<CanonicalRecord> records = List.of(new CanonicalRecord("r1", Map.of("country", "Greenland", "stateProvince", "A")));
        PhaseGroupCache cache = new PhaseGroupCache(records);
        List<RecordGroup> byCountry = cache.groupsFor(List.of("country"));
        List<RecordGroup> byState = cache.groupsFor(List.of("stateProvince"));

        cache.invalidate(Set.of("country"));

        assertThat(cache.groupsFor(List.of("stateProvince"))).isSameAs(byState);
        assertThat(cache.groupsFor(List.of("country"))).isNotSameAs(byCountry).isEqualTo(byCountry);
    }

    @Test
    void invalidateDiscardsAMultiFieldPartitionThatOverlapsAChangedField() {
        List<CanonicalRecord> records = List.of(new CanonicalRecord("r1", Map.of("country", "Greenland", "stateProvince", "A")));
        PhaseGroupCache cache = new PhaseGroupCache(records);
        List<RecordGroup> combined = cache.groupsFor(List.of("country", "stateProvince"));

        cache.invalidate(Set.of("stateProvince"));

        assertThat(cache.groupsFor(List.of("country", "stateProvince"))).isNotSameAs(combined);
    }

    @Test
    void invalidateWithNoOverlapLeavesCacheUntouched() {
        List<CanonicalRecord> records = List.of(new CanonicalRecord("r1", Map.of("country", "Greenland")));
        PhaseGroupCache cache = new PhaseGroupCache(records);
        List<RecordGroup> byCountry = cache.groupsFor(List.of("country"));

        cache.invalidate(Set.of("eventDate"));

        assertThat(cache.groupsFor(List.of("country"))).isSameAs(byCountry);
    }

    @Test
    void invalidateReflectsRecordMutationsMadeAfterTheFirstPartition() {
        CanonicalRecord record = new CanonicalRecord("r1", Map.of("country", "Greenland"));
        PhaseGroupCache cache = new PhaseGroupCache(List.of(record));
        cache.groupsFor(List.of("country"));

        record.terms().put("country", "Denmark");
        cache.invalidate(Set.of("country"));

        assertThat(cache.groupsFor(List.of("country")).get(0).representative().terms()).containsEntry("country", "Denmark");
    }
}
