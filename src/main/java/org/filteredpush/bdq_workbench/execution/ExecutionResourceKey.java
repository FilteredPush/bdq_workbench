/** ExecutionResourceKey.java
 *
 * Identifies the (likely) shared resource that a bound test's invocations use, so invocations of
 * every test using that resource can share one concurrency lane.
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

import java.util.Locale;
import java.util.Objects;

/**
 * A normalized resource lane identifier such as {@code source:worms},
 * {@code class:org.example.Dq}, or {@code method:org.example.Dq#validationXNotempty}.
 *
 * <p>The prefix records how the key was derived (see {@link ExecutionResourceClassifier}):
 * {@code lane:} for an explicitly configured lane name, {@code source:} for a source authority
 * named by a test's parameters, {@code class:} for the implementation class of likely external
 * work, {@code local:} for clearly local work, {@code method:} for unclassified work, and
 * {@code unrestricted} when lane scheduling is disabled. Keys are compared by {@link #id()} only.
 *
 * @param id the normalized identifier; never blank
 */
public record ExecutionResourceKey(String id) {

	/** The single key every binding shares when lane scheduling is disabled. */
	public static final ExecutionResourceKey UNRESTRICTED = new ExecutionResourceKey("unrestricted");

	/**
	 * Validates the identifier.
	 *
	 * @param id the normalized identifier
	 * @throws IllegalArgumentException if {@code id} is null or blank
	 */
	public ExecutionResourceKey {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Resource key id must not be blank");
		}
	}

	/**
	 * Creates the key for an explicitly configured lane name.
	 *
	 * @param name the configured lane name
	 * @return {@code lane:<normalized name>}
	 */
	public static ExecutionResourceKey lane(String name) {
		return new ExecutionResourceKey("lane:" + normalize(name));
	}

	/**
	 * Creates the key for a source authority identified from a test's parameters.
	 *
	 * @param source the source authority description (a parameter value, or a description of the
	 *     parameter names when only defaults are used)
	 * @return {@code source:<normalized source>}
	 */
	public static ExecutionResourceKey source(String source) {
		return new ExecutionResourceKey("source:" + normalize(source));
	}

	/**
	 * Creates the conservative fallback key for likely external work: its implementation class.
	 *
	 * @param implementationClass the implementation class name
	 * @return {@code class:<implementation class>}
	 */
	public static ExecutionResourceKey implementationClass(String implementationClass) {
		return new ExecutionResourceKey("class:" + Objects.requireNonNull(implementationClass).trim());
	}

	/**
	 * Creates the key for clearly local work.
	 *
	 * @param implementationClass the implementation class name
	 * @param method the implementation method name
	 * @return {@code local:<implementation class>#<method>}
	 */
	public static ExecutionResourceKey local(String implementationClass, String method) {
		return new ExecutionResourceKey("local:" + implementationClass + "#" + method);
	}

	/**
	 * Creates the key for ordinary, unclassified work: its implementation class and method.
	 *
	 * @param implementationClass the implementation class name
	 * @param method the implementation method name
	 * @return {@code method:<implementation class>#<method>}
	 */
	public static ExecutionResourceKey method(String implementationClass, String method) {
		return new ExecutionResourceKey("method:" + implementationClass + "#" + method);
	}

	/**
	 * Normalizes a free-text resource description into a stable key fragment: lower case, with
	 * runs of characters other than letters, digits, {@code .}, {@code +}, {@code =} and {@code _}
	 * collapsed to a single {@code -}, and long values truncated, so that for example
	 * {@code "World Register of Marine Species (WoRMS)"} becomes
	 * {@code world-register-of-marine-species-worms}.
	 *
	 * @param raw the raw description
	 * @return the normalized fragment, or {@code unnamed} if nothing usable remains
	 */
	static String normalize(String raw) {
		String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		String collapsed = lower.replaceAll("[^a-z0-9.+=_]+", "-").replaceAll("^-+|-+$", "");
		if (collapsed.length() > 120) {
			collapsed = collapsed.substring(0, 120);
		}
		return collapsed.isEmpty() ? "unnamed" : collapsed;
	}

	/**
	 * Returns the identifier.
	 *
	 * @return {@link #id()}
	 */
	@Override
	public String toString() {
		return id;
	}
}
