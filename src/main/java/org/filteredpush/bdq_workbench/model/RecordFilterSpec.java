/** RecordFilterSpec.java
 *
 * Immutable user-configured record filter criteria for pre-execution dataset filtering.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.app.AppException;

/**
 * User-configured record filter criteria.
 *
 * <p>Each entry identifies a Darwin Core field and one or more exact-match values. Multiple values
 * within a field are ORed; multiple fields are ANDed.
 *
 * @param criteria requested field names mapped to allowed values
 */
public record RecordFilterSpec(Map<String, List<String>> criteria) {

	/**
	 * Canonical constructor; defensively copies criteria and preserves encounter order.
	 */
	public RecordFilterSpec {
		Map<String, List<String>> copied = new LinkedHashMap<>();
		(criteria == null ? Map.<String, List<String>>of() : criteria).forEach((field, values) -> {
			LinkedHashSet<String> dedupedValues = new LinkedHashSet<>(values == null ? List.of() : values);
			copied.put(field, List.copyOf(dedupedValues));
		});
		criteria = Collections.unmodifiableMap(copied);
	}

	/**
	 * @return an empty filter specification
	 */
	public static RecordFilterSpec empty() {
		return new RecordFilterSpec(Map.of());
	}

	/**
	 * Parses a property/GUI string of the form
	 * {@code field=value1|value2;otherField=value3}.
	 *
	 * @param raw the raw filter string
	 * @return the parsed filter specification, or {@link #empty()} if blank
	 * @throws AppException if any clause is malformed or contains a blank field/value
	 */
	public static RecordFilterSpec parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return empty();
		}
		Map<String, LinkedHashSet<String>> parsed = new LinkedHashMap<>();
		for (String clause : raw.split(";")) {
			String trimmedClause = clause.trim();
			if (trimmedClause.isEmpty()) {
				continue;
			}
			int separator = trimmedClause.indexOf('=');
			if (separator <= 0 || separator == trimmedClause.length() - 1) {
				throw new AppException("Invalid record filter: " + trimmedClause
						+ " (expected field=value or field=value1|value2)");
			}
			String field = trimmedClause.substring(0, separator).trim();
			if (field.isEmpty()) {
				throw new AppException("Invalid record filter: field name must not be blank");
			}
			LinkedHashSet<String> values = parsed.computeIfAbsent(field, key -> new LinkedHashSet<>());
			for (String rawValue : trimmedClause.substring(separator + 1).split("\\|", -1)) {
				String value = rawValue.trim();
				if (value.isEmpty()) {
					throw new AppException("Invalid record filter for " + field
							+ ": values must not be blank");
				}
				values.add(value);
			}
		}
		Map<String, List<String>> normalized = new LinkedHashMap<>();
		parsed.forEach((field, values) -> normalized.put(field, List.copyOf(values)));
		return new RecordFilterSpec(normalized);
	}

	/**
	 * @return {@code true} if no filter criteria are configured
	 */
	public boolean isEmpty() {
		return criteria.isEmpty();
	}

	/**
	 * Renders this specification in property/CLI form.
	 *
	 * @return {@code field=value1|value2;otherField=value3}, or {@code ""} if empty
	 */
	public String toPropertyString() {
		List<String> parts = new ArrayList<>();
		criteria.forEach((field, values) -> parts.add(field + "=" + String.join("|", values)));
		return String.join("; ", parts);
	}
}
