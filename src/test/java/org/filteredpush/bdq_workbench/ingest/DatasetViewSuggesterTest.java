/** DatasetViewSuggesterTest.java
 *
 * Tests for {@link DatasetViewSuggester}.
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;
import org.filteredpush.bdq_workbench.model.TableSchema;
import org.junit.jupiter.api.Test;

/**
 * Verifies dataset-view suggestions against representative relational schemas.
 */
class DatasetViewSuggesterTest {

	@Test
	void suggestsOccurrenceGrainMappingsAcrossAnEventRelationship() {
		DatasetSchema schema = new DatasetSchema(
				List.of(
						new TableSchema("event", "event", "EVENT", "eventID",
								List.of("eventID", "eventDate", "decimalLatitude", "decimalLongitude")),
						new TableSchema("occurrence", "occurrence", "OCCURRENCE", "occurrenceID",
								List.of("occurrenceID", "eventID", "scientificName"))),
				List.of(new RelationshipSchema("occurrence", "eventID", "event", "eventID", "occurrence")),
				"fp");

		DatasetView view = new DatasetViewSuggester().suggest(
				schema,
				"occurrence",
				List.of("occurrenceID", "scientificName", "eventDate", "decimalLatitude"));

		assertThat(view.grainTable()).isEqualTo("occurrence");
		assertThat(view.joins()).singleElement().satisfies(join -> {
			assertThat(join.relationName()).isEqualTo("event");
			assertThat(join.sourceTable()).isEqualTo("event");
		});
		assertThat(view.mappings())
				.anySatisfy(mapping -> {
					assertThat(mapping.term()).isEqualTo("occurrenceID");
					assertThat(mapping.sourceTable()).isEqualTo("occurrence");
				})
				.anySatisfy(mapping -> {
					assertThat(mapping.term()).isEqualTo("scientificName");
					assertThat(mapping.sourceTable()).isEqualTo("occurrence");
				})
				.anySatisfy(mapping -> {
					assertThat(mapping.term()).isEqualTo("eventDate");
					assertThat(mapping.sourceTable()).isEqualTo("event");
				})
				.anySatisfy(mapping -> {
					assertThat(mapping.term()).isEqualTo("decimalLatitude");
					assertThat(mapping.sourceTable()).isEqualTo("event");
				});
	}
}
