/** SchemaFingerprint.java
 *
 * Deterministic schema fingerprint helper.
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;
import org.filteredpush.bdq_workbench.model.TableSchema;

/**
 * Computes a stable schema-shape fingerprint.
 */
final class SchemaFingerprint {

	private SchemaFingerprint() {
	}

	/**
	 * Builds a fingerprint from table names, row types and column-name sets.
	 *
	 * @param tables discovered tables
	 * @return fingerprint hash
	 */
	static String of(List<TableSchema> tables, List<RelationshipSchema> relationships) {
		String tablePart = tables.stream()
				.sorted(Comparator.comparing(TableSchema::name))
				.map(table -> table.name().toLowerCase()
						+ "|"
						+ table.rowType().toLowerCase()
						+ "|"
						+ table.columns().stream().map(String::toLowerCase).sorted().collect(Collectors.joining(",")))
				.collect(Collectors.joining("||"));
		String relationshipPart = relationships.stream()
				.sorted(Comparator.comparing(RelationshipSchema::relationName)
						.thenComparing(RelationshipSchema::fromTable)
						.thenComparing(RelationshipSchema::fromColumn)
						.thenComparing(RelationshipSchema::toTable)
						.thenComparing(RelationshipSchema::toColumn))
				.map(relationship -> relationship.relationName().toLowerCase()
						+ "|"
						+ relationship.fromTable().toLowerCase()
						+ "."
						+ relationship.fromColumn().toLowerCase()
						+ "->"
						+ relationship.toTable().toLowerCase()
						+ "."
						+ relationship.toColumn().toLowerCase())
				.collect(Collectors.joining("||"));
		String canonical = tablePart + "##" + relationshipPart;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
