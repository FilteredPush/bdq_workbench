/** RecordDataset.java
 *
 * Collection of canonical records produced by ingestion and passed through execution.
 *
 * Copyright 2026 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.filteredpush.bdq_workbench.model;

import java.util.List;

/**
 * Collection of canonical records.
 *
 * @param records the canonical records in this dataset
 */
public record RecordDataset(List<CanonicalRecord> records) {
    /**
     * Creates an independent copy of this dataset, with each record deep-copied via
     * {@link CanonicalRecord#copy()}.
     *
     * @return a new {@code RecordDataset} containing copies of all records
     */
    public RecordDataset copy() {
        return new RecordDataset(records.stream().map(CanonicalRecord::copy).toList());
    }
}
