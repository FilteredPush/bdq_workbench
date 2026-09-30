/** ExecutionResourceClassifier.java
 *
 * Derives the resource lane, resource class, and concurrency limit of a bound test from explicit
 * policy overrides and conservative, explainable static hints.
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;

/**
 * Assigns each binding to a resource lane.
 *
 * <p>The execution environment cannot tell whether an implementation calls an external service,
 * so the classification combines explicit configuration with conservative hints, in priority
 * order:
 * <ol>
 *   <li><b>Explicit override</b> from {@link ExecutionPolicy#overrides()}, looked up by test ID,
 *   then full implementation signature ({@code class#method(types)}), then implementation key
 *   ({@code class#method}), then implementation class; the first match wins. An override may set
 *   the class, name a shared lane, and/or set a limit.</li>
 *   <li><b>Source-authority parameters.</b> A {@code PARAMETER} whose name looks like a source
 *   authority or service ({@code bdq:sourceAuthority}, {@code bdq:taxonIsMarine},
 *   {@code bdq:geospatialLand}, anything containing "authority", "service", "endpoint", …) makes
 *   the binding likely external. So does such a parameter on another discovered implementation of
 *   the same test, since a "defaults" implementation usually wraps the parameterized one with the
 *   default authorities. Supplied parameter values name the lane ({@code source:<value>}), so
 *   every test using the same authority shares it; with only defaults the lane is named after the
 *   implementation class and parameter names.</li>
 *   <li><b>Implementation class</b> ({@code class:<class>}), the conservative lane for work
 *   configured external without a better key.</li>
 *   <li><b>Implementation method</b> for everything else: {@code local:<class>#<method>} when the
 *   method name marks a clearly local test (NOTEMPTY, INRANGE) and nothing suggests a source
 *   authority, otherwise {@code method:<class>#<method>} (unclassified). STANDARD and other tests
 *   are deliberately not assumed local: they often look values up in a remote vocabulary.</li>
 * </ol>
 * A source-authority parameter can also name a local data layer, which is why overrides win and
 * why lanes classified only by hint may be relaxed at runtime after sustained success (see
 * {@link ExecutionPolicy#localPromotionSuccesses()}).
 *
 * <p>Lane limits: {@link ExecutionPolicy#resourceConcurrency()} for the resulting key, else the
 * override's limit, else the class default; every limit is capped at the worker count.
 */
public final class ExecutionResourceClassifier {

	private static final Pattern AUTHORITY_PARAMETER = Pattern.compile(
			".*(authority|taxonismarine|geospatialland|service|endpoint).*");
	private static final Pattern LOCAL_METHOD = Pattern.compile(".*(notempty|inrange).*");

	private final ExecutionPolicy policy;
	private final int workerCount;
	private final Map<String, List<DiscoveredImplementation>> discoveredByTest;

	/**
	 * Creates a classifier for one run.
	 *
	 * @param policy the execution policy supplying overrides and limits
	 * @param workerCount the global worker count, which caps every lane limit
	 * @param discovered every discovered implementation, used to find sibling implementations of
	 *     a binding's test that declare source-authority parameters
	 */
	public ExecutionResourceClassifier(ExecutionPolicy policy, int workerCount, List<DiscoveredImplementation> discovered) {
		this.policy = policy;
		this.workerCount = Math.max(1, workerCount);
		this.discoveredByTest = new java.util.HashMap<>();
		for (DiscoveredImplementation implementation : discovered == null ? List.<DiscoveredImplementation>of() : discovered) {
			if (implementation.providedTestId() != null) {
				discoveredByTest.computeIfAbsent(normalizeTestId(implementation.providedTestId()), key -> new ArrayList<>())
						.add(implementation);
			}
		}
	}

	/**
	 * Classifies one binding.
	 *
	 * @param binding the binding to classify
	 * @return the binding's resource lane assignment
	 */
	public ResourceAssignment classify(ImplementationBinding binding) {
		if (!policy.laneSchedulingEnabled()) {
			return new ResourceAssignment(ExecutionResourceKey.UNRESTRICTED, ResourceClass.UNCLASSIFIED,
					"lane scheduling disabled", workerCount, true);
		}
		Hint hint = staticHint(binding);
		String overrideTarget = overrideTarget(binding);
		ExecutionPolicy.ResourceOverride override = overrideTarget == null ? null : policy.overrides().get(overrideTarget);

		ResourceClass resourceClass = hint.resourceClass();
		ExecutionResourceKey key = hint.key();
		String reason = hint.reason();
		boolean pinned = false;
		Integer limit = null;
		if (override != null) {
			pinned = true;
			if (override.resourceClass() != null && override.resourceClass() != resourceClass) {
				resourceClass = override.resourceClass();
				key = defaultKeyFor(resourceClass, binding, hint);
			}
			if (override.lane() != null) {
				key = ExecutionResourceKey.lane(override.lane());
			}
			limit = override.maxConcurrency();
			reason = "override for " + overrideTarget + " (" + describe(override) + "); static hint was: " + hint.reason();
		}
		Integer laneLimit = policy.resourceConcurrency().get(key.id());
		if (laneLimit != null) {
			limit = laneLimit;
			pinned = true;
			reason = reason + "; lane limit " + laneLimit + " configured for " + key.id();
		}
		int maxConcurrency = Math.min(workerCount, limit != null ? limit : defaultLimit(resourceClass));
		return new ResourceAssignment(key, resourceClass, reason, maxConcurrency, pinned);
	}

	/**
	 * Returns the default limit of a resource class.
	 *
	 * @param resourceClass the class
	 * @return the policy's default limit for {@code resourceClass}
	 */
	private int defaultLimit(ResourceClass resourceClass) {
		return switch (resourceClass) {
			case LOCAL -> policy.localConcurrency() == 0 ? workerCount : policy.localConcurrency();
			case EXTERNAL -> policy.externalConcurrency();
			case UNCLASSIFIED -> policy.unclassifiedConcurrency();
		};
	}

	/**
	 * Finds the highest-precedence override target matching the binding.
	 *
	 * @param binding the binding
	 * @return the matching target (test ID, full signature, implementation key, or class), or
	 *     {@code null} if no override applies
	 */
	private String overrideTarget(ImplementationBinding binding) {
		if (policy.overrides().isEmpty()) {
			return null;
		}
		if (binding.testId() != null) {
			if (policy.overrides().containsKey(binding.testId())) {
				return binding.testId();
			}
			String normalized = normalizeTestId(binding.testId());
			for (String target : policy.overrides().keySet()) {
				if (normalizeTestId(target).equals(normalized)) {
					return target;
				}
			}
		}
		for (String candidate : List.of(
				binding.fullImplementationSignature(),
				binding.legacyImplementationKey(),
				binding.implementationClass())) {
			if (candidate != null && policy.overrides().containsKey(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Returns the key a class implies when an override changes the class without naming a lane.
	 *
	 * @param resourceClass the overriding class
	 * @param binding the binding
	 * @param hint the static hint, whose source key is kept when the override agrees it is external
	 * @return the key
	 */
	private static ExecutionResourceKey defaultKeyFor(ResourceClass resourceClass, ImplementationBinding binding, Hint hint) {
		return switch (resourceClass) {
			case LOCAL -> ExecutionResourceKey.local(binding.implementationClass(), binding.implementationMethod());
			case EXTERNAL -> hint.sourceKey() != null
					? hint.sourceKey()
					: ExecutionResourceKey.implementationClass(binding.implementationClass());
			case UNCLASSIFIED -> ExecutionResourceKey.method(binding.implementationClass(), binding.implementationMethod());
		};
	}

	/**
	 * Computes the static hint for a binding.
	 *
	 * @param binding the binding
	 * @return the hint
	 */
	Hint staticHint(ImplementationBinding binding) {
		Set<String> authorityNames = new TreeSet<>();
		Set<String> authorityValues = new TreeSet<>();
		for (BoundMethodParameter bound : binding.parameterBindings() == null ? List.<BoundMethodParameter>of() : binding.parameterBindings()) {
			if (bound.parameter().role() == ParameterRole.PARAMETER && isAuthorityParameter(bound.parameter().source())) {
				authorityNames.add(localName(bound.parameter().source()));
				if (bound.suppliedValue() != null && !bound.suppliedValue().isBlank()) {
					authorityValues.add(bound.suppliedValue().trim());
				}
			}
		}
		binding.parameters().forEach((name, value) -> {
			if (isAuthorityParameter(name)) {
				authorityNames.add(localName(name));
				if (value != null && !value.isBlank()) {
					authorityValues.add(value.trim());
				}
			}
		});
		boolean fromSibling = false;
		if (authorityNames.isEmpty() && binding.testId() != null) {
			for (DiscoveredImplementation sibling : discoveredByTest.getOrDefault(normalizeTestId(binding.testId()), List.of())) {
				for (MethodParameter parameter : sibling.parameters()) {
					if (parameter.role() == ParameterRole.PARAMETER && isAuthorityParameter(parameter.source())) {
						authorityNames.add(localName(parameter.source()));
						fromSibling = true;
					}
				}
			}
		}
		if (!authorityNames.isEmpty()) {
			ExecutionResourceKey key = authorityValues.isEmpty()
					? ExecutionResourceKey.source(simpleName(binding.implementationClass()) + ":" + String.join("+", authorityNames) + "=default")
					: ExecutionResourceKey.source(String.join("+", authorityValues));
			String reason = fromSibling
					? "likely external: another implementation of this test declares source-authority parameter(s) "
							+ authorityNames + ", so this one presumably uses their defaults"
					: "likely external: source-authority parameter(s) " + authorityNames
							+ (authorityValues.isEmpty() ? " using defaults" : " = " + authorityValues);
			return new Hint(ResourceClass.EXTERNAL, key, key, reason);
		}
		String method = binding.implementationMethod() == null ? "" : binding.implementationMethod().toLowerCase(Locale.ROOT);
		if (LOCAL_METHOD.matcher(method).matches()) {
			return new Hint(ResourceClass.LOCAL,
					ExecutionResourceKey.local(binding.implementationClass(), binding.implementationMethod()), null,
					"local: method name marks a NOTEMPTY/INRANGE test and no source-authority parameter is declared");
		}
		return new Hint(ResourceClass.UNCLASSIFIED,
				ExecutionResourceKey.method(binding.implementationClass(), binding.implementationMethod()), null,
				"unclassified: no source-authority parameter and no clearly local method name");
	}

	/**
	 * Returns whether a parameter name looks like a source authority or service.
	 *
	 * @param name the parameter name, for example {@code bdq:sourceAuthority}
	 * @return {@code true} if the name suggests an authority/service parameter
	 */
	static boolean isAuthorityParameter(String name) {
		return name != null && AUTHORITY_PARAMETER.matcher(localName(name).toLowerCase(Locale.ROOT)).matches();
	}

	/**
	 * Strips a namespace prefix or IRI path from a term or parameter name.
	 *
	 * @param name the name
	 * @return the part after the last {@code :}, {@code /} or {@code #}
	 */
	private static String localName(String name) {
		String trimmed = name.trim();
		int cut = Math.max(trimmed.lastIndexOf(':'), Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('#')));
		return cut >= 0 ? trimmed.substring(cut + 1) : trimmed;
	}

	/**
	 * Returns a class name without its package.
	 *
	 * @param className the class name
	 * @return the simple name
	 */
	private static String simpleName(String className) {
		if (className == null) {
			return "";
		}
		int dot = className.lastIndexOf('.');
		return dot >= 0 ? className.substring(dot + 1) : className;
	}

	/**
	 * Normalizes a test identifier for comparison: lower case, without a {@code urn:uuid:} prefix.
	 *
	 * @param testId the identifier
	 * @return the normalized identifier
	 */
	private static String normalizeTestId(String testId) {
		String lower = testId.trim().toLowerCase(Locale.ROOT);
		return lower.startsWith("urn:uuid:") ? lower.substring("urn:uuid:".length()) : lower;
	}

	/**
	 * Describes an override for diagnostics.
	 *
	 * @param override the override
	 * @return a compact description
	 */
	private static String describe(ExecutionPolicy.ResourceOverride override) {
		List<String> parts = new ArrayList<>();
		if (override.resourceClass() != null) {
			parts.add(override.resourceClass().name());
		}
		if (override.lane() != null) {
			parts.add("lane=" + override.lane());
		}
		if (override.maxConcurrency() != null) {
			parts.add("max=" + override.maxConcurrency());
		}
		return String.join(", ", parts);
	}

	/**
	 * A static classification hint.
	 *
	 * @param resourceClass the hinted class
	 * @param key the hinted lane key
	 * @param sourceKey the source-authority key, if the hint found one, else {@code null}
	 * @param reason the explanation
	 */
	record Hint(ResourceClass resourceClass, ExecutionResourceKey key, ExecutionResourceKey sourceKey, String reason) {
	}
}
