/** JoinPolicyResolver.java
 *
 * Asks the user how to handle multiplicity for related tables that have more than one row per grain record.
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
package org.filteredpush.bdq_workbench.ingest;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;

/**
 * Supplies multiplicity-handling decisions that were not given up front.
 *
 * <p>When a multi-table dataset is ingested without a dataset view, {@link DefaultIngestService}
 * builds one automatically. Related tables with at most one row per grain record need no
 * decision; for each table with more, the user must choose a {@link DatasetViewCardinalityPolicy}.
 * Decisions come first from configuration ({@code --join-policy}); any still missing are passed to
 * this resolver, which may ask the user (for example on an interactive console). Tables it leaves
 * undecided stop the run with a {@link DatasetViewRequiredException}.
 */
@FunctionalInterface
public interface JoinPolicyResolver {

	/** A resolver that decides nothing, so undecided tables always stop the run. */
	JoinPolicyResolver NONE = (grainTable, undecided) -> Map.of();

	/**
	 * Asks for a policy for each related table that has more than one row per grain record.
	 *
	 * @param grainTable the grain table the view is built around
	 * @param undecided the related tables still needing a decision, with their observed
	 *     multiplicity
	 * @return policies keyed by related table name; tables left out remain undecided
	 */
	Map<String, DatasetViewCardinalityPolicy> resolve(String grainTable, List<ViewRelation> undecided);
}
