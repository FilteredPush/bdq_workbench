/** RecordGroupPartitioner.java
 *
 * Partitions canonical records into groups sharing identical values for a given set of Darwin Core term names.
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.EvaluationSubject;

/**
 * Pure partitioning function turning a list of evaluation subjects into {@link RecordGroup}s of
 * subjects that share identical values for a given ordered set of Darwin Core term names.
 *
 * <p>Grouping is exact-match only: term values are compared with plain {@link String#equals}, with
 * no case-folding or other normalization, since many BDQ tests are sensitive to exact formatting. A
 * subject missing one of {@code fields} entirely groups together with every other subject also
 * missing that same field, rather than being treated as a singleton or throwing.
 *
 * <p>Callers must pass exactly the term name(s) a binding's implementation will actually read at
 * invocation time (i.e. each
 * {@link org.filteredpush.bdq_workbench.model.BoundMethodParameter#resolvedSource()} for its
 * {@code ACTED_UPON}/{@code CONSULTED} parameters) so the partition reflects precisely the values
 * that would otherwise have been read once per subject.
 */
final class RecordGroupPartitioner {

    /**
     * Sentinel substituted for a missing or {@code null} term value when building a group key, so
     * that subjects missing the same field still group together (a real Darwin Core value is never
     * expected to collide with this sentinel).
     */
    static final String NULL_SENTINEL = "<<NULL>>";

    private RecordGroupPartitioner() {
    }

    /**
     * Partitions {@code subjects} into groups sharing identical values for {@code fields}.
     *
     * @param subjects the subjects to partition, in original order
     * @param fields the Darwin Core term names to group by; an empty list groups every record into
     *     a single group, since a test declaring no such terms is invariant across all of them
     * @return one {@link RecordGroup} per distinct combination of values for {@code fields}, in
     *     first-encountered order
     */
    static List<RecordGroup> partition(List<EvaluationSubject> subjects, List<String> fields) {
        Map<List<String>, List<EvaluationSubject>> membersByKey = new LinkedHashMap<>();
        for (EvaluationSubject subject : subjects) {
            membersByKey.computeIfAbsent(keyFor(subject, fields), key -> new ArrayList<>()).add(subject);
        }
        List<RecordGroup> groups = new ArrayList<>(membersByKey.size());
        for (List<EvaluationSubject> members : membersByKey.values()) {
            groups.add(new RecordGroup(members.get(0), List.copyOf(members)));
        }
        return groups;
    }

    /**
     * Builds the exact-match group key for one subject: {@code fields.size()} values, each either
     * the subject's raw term value or {@link #NULL_SENTINEL} if absent/{@code null}.
     *
     * @param subject the subject to build a key for
     * @param fields the term names to read, in order
     * @return the subject's group key
     */
    private static List<String> keyFor(EvaluationSubject subject, List<String> fields) {
        List<String> key = new ArrayList<>(fields.size());
        for (String field : fields) {
            String value = subject.effectiveRecord().terms().get(field);
            key.add(value == null ? NULL_SENTINEL : value);
        }
        return List.copyOf(key);
    }
}
