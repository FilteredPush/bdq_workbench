/** PhaseGroupCache.java
 *
 * Phase-scoped cache of distinct-value record partitions, shared across every binding within a single phase execution that declares the same set of Darwin Core term names as its input.
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
package org.filteredpush.bdq_workbench.execution;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches {@link RecordGroupPartitioner} results per distinct field set for the lifetime of one
 * phase execution.
 *
 * <p>Two bindings that declare the same set of Darwin Core term names as input — regardless of
 * test type, or whether a given term is read as {@code ACTED_UPON} or {@code CONSULTED} — share
 * one partitioning pass over the phase's records rather than each recomputing it independently.
 * The field-set key is expected to already be canonicalized (sorted and deduplicated) by the
 * caller, so that e.g. {@code ["country", "stateProvince"]} and {@code ["stateProvince", "country"]}
 * are recognized as the same key.
 *
 * <p>Instances are scoped to a single phase execution (a new instance per {@code executePhase}
 * call) and are not thread-safe: {@link #groupsFor} and {@link #invalidate} are only ever called
 * from the orchestrating thread, never from the worker threads that actually invoke tests, so no
 * synchronization is needed.
 *
 * <p>For phases where a binding's invocation can change the record values a later binding in the
 * same phase reads (the AMENDMENT phase), {@link #invalidate} must be called with the fields that
 * were just changed, immediately after applying them and before computing the next binding's
 * groups — see {@link ParallelPhaseExecutionService} for where this is done. PRE_AMENDMENT and
 * POST_AMENDMENT never mutate records mid-phase, so a cache used for either of those never needs
 * invalidation at all.
 */
final class PhaseGroupCache {
    private static final Logger LOG = LoggerFactory.getLogger(PhaseGroupCache.class);

    private final List<CanonicalRecord> records;
    private final Map<List<String>, List<RecordGroup>> cache = new HashMap<>();

    /**
     * Creates a cache over the given phase's records.
     *
     * @param records the phase's records to partition on demand; not copied, so later mutations
     *     (e.g. amendments applied elsewhere) are visible to any partition computed afterward
     */
    PhaseGroupCache(List<CanonicalRecord> records) {
        this.records = records;
    }

    /**
     * Returns the distinct-value groups for {@code fields}, computing and caching them on first
     * request for that exact field set and returning the cached result on every subsequent
     * request, until {@link #invalidate} discards it.
     *
     * @param fields the canonical (sorted, deduplicated) Darwin Core term names to partition by
     * @return the distinct-value groups for {@code fields}
     */
    List<RecordGroup> groupsFor(List<String> fields) {
        return cache.computeIfAbsent(fields, f -> {
            List<RecordGroup> groups = RecordGroupPartitioner.partition(records, f);
            LOG.debug("Partitioned {} records into {} distinct groups for fields {}", records.size(), groups.size(), f);
            return groups;
        });
    }

    /**
     * Discards every cached partition whose field set overlaps {@code changedFields}, forcing the
     * next {@link #groupsFor} call for one of those field sets to recompute it from the records'
     * current values. Partitions for field sets disjoint from {@code changedFields} are left
     * cached and keep being shared.
     *
     * @param changedFields the Darwin Core term names an amendment just changed; a no-op if empty
     */
    void invalidate(Set<String> changedFields) {
        if (changedFields.isEmpty()) {
            return;
        }
        int before = cache.size();
        cache.keySet().removeIf(fields -> !Collections.disjoint(fields, changedFields));
        int removed = before - cache.size();
        if (removed > 0) {
            LOG.debug("Invalidated {} cached partition(s) touching changed fields {}", removed, changedFields);
        }
    }
}
