/** RecordGroup.java
 *
 * A group of canonical records sharing identical values for the set of Darwin Core terms a test declares as input, together with the representative record a test should actually be invoked against for the whole group.
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

import java.util.List;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;

/**
 * One distinct-value group of records, produced by {@link RecordGroupPartitioner}.
 *
 * <p>Every record in {@link #memberRecordIds()} (including {@link #representative()}'s own ID)
 * shares identical values for the field set the group was partitioned by; a test is invoked once
 * against {@link #representative()} and its {@link org.filteredpush.bdq_workbench.model.Response}
 * is then applied to every member record, on the assumption that a test is a pure function of its
 * declared inputs.
 *
 * @param representative one member of the group, chosen (in original record order) as the record
 *     a test is actually invoked against
 * @param memberRecordIds every record ID sharing this group's values, including
 *     {@code representative}'s own ID, in original record order
 */
record RecordGroup(CanonicalRecord representative, List<String> memberRecordIds) {
}
