/** ResourceClass.java
 *
 * How likely a bound test implementation is to depend on a shared external resource, which
 * decides the default concurrency of the resource lane its invocations run in.
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

/**
 * Classification of a binding's likely resource use. The workbench cannot know a priori whether a
 * discovered implementation calls an external service, so these are hints (see
 * {@link ExecutionResourceClassifier}), not facts: explicit overrides and runtime learning can
 * supersede them.
 */
public enum ResourceClass {
	/** Clearly local work (for example a NOTEMPTY test); may use every worker. */
	LOCAL,
	/** Likely to call an external service or source authority; conservatively limited. */
	EXTERNAL,
	/** Nothing is known either way; moderately limited until runtime outcomes say more. */
	UNCLASSIFIED
}
