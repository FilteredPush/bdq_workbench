package org.filteredpush.bdq_workbench.model;

import java.util.List;

/** Collection of canonical records. */
public record RecordDataset(List<CanonicalRecord> records) {
    public RecordDataset copy() {
        return new RecordDataset(records.stream().map(CanonicalRecord::copy).toList());
    }
}
