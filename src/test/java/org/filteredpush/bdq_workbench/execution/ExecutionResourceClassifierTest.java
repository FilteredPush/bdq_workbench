/** ExecutionResourceClassifierTest.java
 *
 * Tests resource key derivation, static classification hints, and override precedence.
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.junit.jupiter.api.Test;

class ExecutionResourceClassifierTest {

	private static final String GEO = "org.example.GeoDQ";
	private static final String GEO_DEFAULTS = "org.example.GeoDQDefaults";

	@Test
	void suppliedSourceAuthorityMakesBindingExternalAndNamesTheLaneAfterTheAuthority() {
		ExecutionResourceClassifier classifier = new ExecutionResourceClassifier(ExecutionPolicy.defaults(), 8, List.of());
		ImplementationBinding binding = ExecutionTestSupport.withParameter(
				ExecutionTestSupport.binding("urn:uuid:t1", TestType.VALIDATION, GEO, "validationScientificnameFound", Phase.PRE_AMENDMENT, "scientificName"),
				"bdq:sourceAuthority", "World Register of Marine Species (WoRMS)");

		ResourceAssignment assignment = classifier.classify(binding);

		assertThat(assignment.resourceClass()).isEqualTo(ResourceClass.EXTERNAL);
		assertThat(assignment.key().id()).isEqualTo("source:world-register-of-marine-species-worms");
		assertThat(assignment.maxConcurrency()).isEqualTo(ExecutionPolicy.DEFAULT_EXTERNAL_CONCURRENCY);
		assertThat(assignment.pinned()).isFalse();
		assertThat(assignment.reason()).contains("sourceAuthority");
	}

	@Test
	void testsUsingTheSameAuthorityShareOneLane() {
		ExecutionResourceClassifier classifier = new ExecutionResourceClassifier(ExecutionPolicy.defaults(), 8, List.of());
		ImplementationBinding first = ExecutionTestSupport.withParameter(
				ExecutionTestSupport.binding("t1", TestType.VALIDATION, "org.example.SciNameDQ", "validationA", Phase.PRE_AMENDMENT, "a"),
				"bdq:sourceAuthority", "WoRMS");
		ImplementationBinding second = ExecutionTestSupport.withParameter(
				ExecutionTestSupport.binding("t2", TestType.AMENDMENT, GEO, "amendmentB", Phase.AMENDMENT, "b"),
				"bdq:taxonIsMarine", "worms");

		assertThat(classifier.classify(first).key()).isEqualTo(classifier.classify(second).key());
	}

	@Test
	void defaultsImplementationIsExternalWhenASiblingImplementationDeclaresAuthorityParameters() throws Exception {
		DiscoveredImplementation parameterized = new DiscoveredImplementation("urn:uuid:TM", null, TestType.VALIDATION,
				Phase.PRE_AMENDMENT, GEO, "validationCoordinatesTerrestrialmarine", null,
				List.of(new MethodParameter(0, "lat", ParameterRole.ACTED_UPON, "dwc:decimalLatitude", "java.lang.String", true),
						new MethodParameter(1, "marine", ParameterRole.PARAMETER, "bdq:taxonIsMarine", "java.lang.String", false),
						new MethodParameter(2, "land", ParameterRole.PARAMETER, "bdq:geospatialLand", "java.lang.String", false)),
				null, Object.class.getMethod("toString"));
		ExecutionResourceClassifier classifier = new ExecutionResourceClassifier(ExecutionPolicy.defaults(), 8, List.of(parameterized));
		ImplementationBinding defaults = ExecutionTestSupport.binding("urn:uuid:tm", TestType.VALIDATION, GEO_DEFAULTS,
				"validationCoordinatesTerrestrialmarine", Phase.PRE_AMENDMENT, "decimalLatitude");

		ResourceAssignment assignment = classifier.classify(defaults);

		assertThat(assignment.resourceClass()).isEqualTo(ResourceClass.EXTERNAL);
		assertThat(assignment.key().id()).isEqualTo("source:geodqdefaults-geospatialland+taxonismarine=default");
		assertThat(assignment.reason()).contains("another implementation");
	}

	@Test
	void clearlyLocalTestsAreUnrestrictedButStandardTestsAreNotAssumedLocal() {
		ExecutionResourceClassifier classifier = new ExecutionResourceClassifier(ExecutionPolicy.defaults(), 8, List.of());

		ResourceAssignment notEmpty = classifier.classify(ExecutionTestSupport.binding("t1", TestType.VALIDATION, GEO,
				"validationCountrycodeNotempty", Phase.PRE_AMENDMENT, "countryCode"));
		ResourceAssignment inRange = classifier.classify(ExecutionTestSupport.binding("t2", TestType.VALIDATION, GEO,
				"validationDecimallatitudeInrange", Phase.PRE_AMENDMENT, "decimalLatitude"));
		ResourceAssignment standard = classifier.classify(ExecutionTestSupport.binding("t3", TestType.VALIDATION, GEO,
				"validationCountrycodeStandard", Phase.PRE_AMENDMENT, "countryCode"));

		assertThat(notEmpty.resourceClass()).isEqualTo(ResourceClass.LOCAL);
		assertThat(notEmpty.maxConcurrency()).isEqualTo(8);
		assertThat(inRange.resourceClass()).isEqualTo(ResourceClass.LOCAL);
		assertThat(standard.resourceClass()).isEqualTo(ResourceClass.UNCLASSIFIED);
		assertThat(standard.key().id()).isEqualTo("method:" + GEO + "#validationCountrycodeStandard");
		assertThat(standard.maxConcurrency()).isEqualTo(ExecutionPolicy.DEFAULT_UNCLASSIFIED_CONCURRENCY);
	}

	@Test
	void sourceAuthorityHintContradictsALocalLookingMethodName() {
		ExecutionResourceClassifier classifier = new ExecutionResourceClassifier(ExecutionPolicy.defaults(), 8, List.of());
		ImplementationBinding binding = ExecutionTestSupport.withParameter(
				ExecutionTestSupport.binding("t1", TestType.VALIDATION, GEO, "validationXInrange", Phase.PRE_AMENDMENT, "x"),
				"bdq:sourceAuthority", "GBIF");

		assertThat(classifier.classify(binding).resourceClass()).isEqualTo(ResourceClass.EXTERNAL);
	}

	@Test
	void overridesWinOverHintsWithTestIdTakingPrecedenceOverImplementation() {
		ExecutionPolicy policy = ExecutionPolicy.builder()
				.override(GEO, new ExecutionPolicy.ResourceOverride(ResourceClass.EXTERNAL, null, null))
				.override("urn:uuid:local-layer", new ExecutionPolicy.ResourceOverride(ResourceClass.LOCAL, null, null))
				.override(GEO + "#validationShared", new ExecutionPolicy.ResourceOverride(null, "WoRMS", 1))
				.build();
		ExecutionResourceClassifier classifier = new ExecutionResourceClassifier(policy, 8, List.of());

		ResourceAssignment byClass = classifier.classify(ExecutionTestSupport.binding("t1", TestType.VALIDATION, GEO,
				"validationCountrycodeNotempty", Phase.PRE_AMENDMENT, "countryCode"));
		ResourceAssignment byTest = classifier.classify(ExecutionTestSupport.withParameter(
				ExecutionTestSupport.binding("urn:uuid:LOCAL-LAYER", TestType.VALIDATION, GEO, "validationLand", Phase.PRE_AMENDMENT, "x"),
				"bdq:geospatialLand", "local shapefile"));
		ResourceAssignment byMethod = classifier.classify(ExecutionTestSupport.binding("t3", TestType.VALIDATION, GEO,
				"validationShared", Phase.PRE_AMENDMENT, "x"));

		assertThat(byClass.resourceClass()).isEqualTo(ResourceClass.EXTERNAL);
		assertThat(byClass.key().id()).isEqualTo("class:" + GEO);
		assertThat(byClass.maxConcurrency()).isEqualTo(2);
		assertThat(byClass.pinned()).isTrue();
		assertThat(byTest.resourceClass()).as("a test-ID override beats the class override and the authority hint")
				.isEqualTo(ResourceClass.LOCAL);
		assertThat(byTest.maxConcurrency()).isEqualTo(8);
		assertThat(byMethod.key().id()).isEqualTo("lane:worms");
		assertThat(byMethod.maxConcurrency()).isEqualTo(1);
	}

	@Test
	void laneLimitByResourceKeyWinsAndEveryLimitIsCappedAtTheWorkerCount() {
		ExecutionPolicy policy = ExecutionPolicy.builder()
				.resourceConcurrency("source:worms", 1)
				.unclassifiedConcurrency(16)
				.build();
		ExecutionResourceClassifier classifier = new ExecutionResourceClassifier(policy, 3, List.of());

		ResourceAssignment worms = classifier.classify(ExecutionTestSupport.withParameter(
				ExecutionTestSupport.binding("t1", TestType.VALIDATION, GEO, "validationA", Phase.PRE_AMENDMENT, "x"),
				"bdq:sourceAuthority", "WoRMS"));
		ResourceAssignment unclassified = classifier.classify(ExecutionTestSupport.binding("t2", TestType.VALIDATION, GEO,
				"validationB", Phase.PRE_AMENDMENT, "x"));

		assertThat(worms.maxConcurrency()).isEqualTo(1);
		assertThat(worms.pinned()).isTrue();
		assertThat(unclassified.maxConcurrency()).isEqualTo(3);
	}

	@Test
	void disablingLaneSchedulingPutsEveryBindingInOneUnrestrictedLane() {
		ExecutionPolicy policy = ExecutionPolicy.builder().laneSchedulingEnabled(false).build();
		ExecutionResourceClassifier classifier = new ExecutionResourceClassifier(policy, 5, List.of());

		ResourceAssignment assignment = classifier.classify(ExecutionTestSupport.withParameter(
				ExecutionTestSupport.binding("t1", TestType.VALIDATION, GEO, "validationA", Phase.PRE_AMENDMENT, "x"),
				"bdq:sourceAuthority", "WoRMS"));

		assertThat(assignment.key()).isEqualTo(ExecutionResourceKey.UNRESTRICTED);
		assertThat(assignment.maxConcurrency()).isEqualTo(5);
	}

	@Test
	void keysAreNormalizedSafely() {
		assertThat(ExecutionResourceKey.source("  Natural Earth / Land (10m)  ").id()).isEqualTo("source:natural-earth-land-10m");
		assertThat(ExecutionResourceKey.lane("").id()).isEqualTo("lane:unnamed");
		assertThat(ExecutionResourceKey.source("x".repeat(500)).id()).hasSize("source:".length() + 120);
	}
}
