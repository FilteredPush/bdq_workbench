/** CanonicalRecord.java
 *
 * Canonical record representation used across ingestion and execution, holding a record's ID and its Darwin Core term values.
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

import java.util.HashMap;
import java.util.Map;

/**
 * Canonical record representation for ingestion and execution.
 *
 * <p>Wraps a record identifier together with a mutable map of its Darwin Core term values, so
 * that {@link org.filteredpush.bdq_workbench.execution.TestExecutionService} and amendment tests
 * can update term values in place as a run progresses.
 */
public final class CanonicalRecord {
    private final String id;
    private final Map<String, String> terms;

    /**
     * Creates a canonical record, copying the supplied terms into a new mutable map.
     *
     * @param id the record's identifier
     * @param terms the record's initial Darwin Core term values, keyed by term name
     */
    public CanonicalRecord(String id, Map<String, String> terms) {
        this.id = id;
        this.terms = new HashMap<>(terms);
    }

    /**
     * Returns this record's identifier.
     *
     * @return the record ID
     */
    public String id() {
        return id;
    }

    /**
     * Returns this record's Darwin Core term values.
     *
     * @return the mutable map of term name to value backing this record
     */
    public Map<String, String> terms() {
        return terms;
    }

    /**
     * Creates an independent copy of this record, with its own copy of the terms map.
     *
     * @return a new {@code CanonicalRecord} with the same ID and a copy of the current term values
     */
    public CanonicalRecord copy() {
        return new CanonicalRecord(id, terms);
    }
}
