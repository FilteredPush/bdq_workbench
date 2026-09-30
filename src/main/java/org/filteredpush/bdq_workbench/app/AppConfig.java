/** AppConfig.java
 *
 * Immutable configuration values controlling a single BDQ Workbench run.
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
package org.filteredpush.bdq_workbench.app;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.execution.ExecutionPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.RecordFilterSpec;

/**
 * Immutable application configuration values.
 *
 * <p>Produced by {@link ConfigLoader} from classpath defaults and command line overrides, and
 * consumed by {@link BdqWorkbenchApplication} and the GUI to wire up the pipeline services
 * (use case resolution, test discovery, and parallel execution) for a run.
 *
 * @param useCaseXml path to the use case XML definition file
 * @param rdfDefinitions RDF/OWL files (e.g. {@code bdqtest.ttl}, {@code bdqffdq.owl}) used to
 *     resolve test/dimension/method metadata referenced by the use case
 * @param datasetPath path to the input dataset, either a Darwin Core Archive zip or a
 *     datapackage.json
 * @param useCaseId optional identifier selecting a specific use case within {@code useCaseXml};
 *     empty to use the default/only use case
 * @param implementationPackages Java package names to scan for annotated test implementations
 * @param threadCount number of worker threads used for parallel test execution, must be at
 *     least 1
 * @param dedupEnabled whether to invoke each test once per distinct combination of values of the
 *     Darwin Core terms it declares as input, rather than once per record, applying the result to
 *     every record sharing that combination; defaults to {@code true}
 * @param recordFilter pre-execution record filter criteria limiting which records enter the BDQ
 *     test pipeline; empty to run against every ingested record
 * @param datasetTable which of the input dataset's tables to run against — a Darwin Core
 *     Archive's core or one of its extensions, or one of a Data Package's resources — named by
 *     its location, resource name or Darwin Core row type; empty to let the ingestor choose
 * @param datasetView optional path to a standalone dataset view JSON file
 * @param datasetJoinPolicies multiplicity-handling policies keyed by related table name, used
 *     when a multi-table dataset is run without {@code datasetView}; tables with more than one row
 *     per grain record need one (see {@link DatasetViewCardinalityPolicy})
 * @param executionPolicy how test execution shares the worker pool among external services and
 *     local work: resource lanes, adaptive throttling, circuit breaking, retries, and
 *     PRE-to-POST result reuse; defaults to {@link ExecutionPolicy#defaults()}
 */
public record AppConfig(
		Path useCaseXml,
		List<Path> rdfDefinitions,
		Path datasetPath,
		String useCaseId,
		List<String> implementationPackages,
		int threadCount,
		boolean dedupEnabled,
		RecordFilterSpec recordFilter,
		String datasetTable,
		String datasetView,
		Map<String, DatasetViewCardinalityPolicy> datasetJoinPolicies,
		ExecutionPolicy executionPolicy) {

	/**
	 * Creates a configuration with the default execution policy.
	 *
	 * @param useCaseXml path to the use case XML definition file
	 * @param rdfDefinitions RDF/OWL files used to resolve policy/test metadata
	 * @param datasetPath path to the dataset input
	 * @param useCaseId optional use case identifier
	 * @param implementationPackages Java packages to scan for test implementations
	 * @param threadCount number of worker threads to use
	 * @param dedupEnabled whether distinct-value execution is enabled
	 * @param recordFilter pre-execution record filter criteria
	 * @param datasetTable which dataset table to run against
	 * @param datasetView optional dataset view JSON path
	 * @param datasetJoinPolicies multiplicity-handling policies keyed by related table name
	 */
	public AppConfig(
			Path useCaseXml,
			List<Path> rdfDefinitions,
			Path datasetPath,
			String useCaseId,
			List<String> implementationPackages,
			int threadCount,
			boolean dedupEnabled,
			RecordFilterSpec recordFilter,
			String datasetTable,
			String datasetView,
			Map<String, DatasetViewCardinalityPolicy> datasetJoinPolicies) {
		this(useCaseXml, rdfDefinitions, datasetPath, useCaseId, implementationPackages, threadCount, dedupEnabled,
				recordFilter, datasetTable, datasetView, datasetJoinPolicies, ExecutionPolicy.defaults());
	}

	/**
	 * Creates a configuration with no join policies.
	 *
	 * @param useCaseXml path to the use case XML definition file
	 * @param rdfDefinitions RDF/OWL files used to resolve policy/test metadata
	 * @param datasetPath path to the dataset input
	 * @param useCaseId optional use case identifier
	 * @param implementationPackages Java packages to scan for test implementations
	 * @param threadCount number of worker threads to use
	 * @param dedupEnabled whether distinct-value execution is enabled
	 * @param recordFilter pre-execution record filter criteria
	 * @param datasetTable which dataset table to run against
	 * @param datasetView optional dataset view JSON path
	 */
	public AppConfig(
			Path useCaseXml,
			List<Path> rdfDefinitions,
			Path datasetPath,
			String useCaseId,
			List<String> implementationPackages,
			int threadCount,
			boolean dedupEnabled,
			RecordFilterSpec recordFilter,
			String datasetTable,
			String datasetView) {
		this(useCaseXml, rdfDefinitions, datasetPath, useCaseId, implementationPackages, threadCount, dedupEnabled,
				recordFilter, datasetTable, datasetView, Map.of());
	}

	/**
	 * Creates a configuration with no record filters.
	 *
	 * @param useCaseXml path to the use case XML definition file
	 * @param rdfDefinitions RDF/OWL files used to resolve policy/test metadata
	 * @param datasetPath path to the dataset input
	 * @param useCaseId optional use case identifier
	 * @param implementationPackages Java packages to scan for test implementations
	 * @param threadCount number of worker threads to use
	 * @param dedupEnabled whether distinct-value execution is enabled
	 */
	public AppConfig(
			Path useCaseXml,
			List<Path> rdfDefinitions,
			Path datasetPath,
			String useCaseId,
			List<String> implementationPackages,
			int threadCount,
			boolean dedupEnabled) {
		this(useCaseXml, rdfDefinitions, datasetPath, useCaseId, implementationPackages, threadCount, dedupEnabled,
				RecordFilterSpec.empty(), "", "");
	}

	/**
	 * Creates a configuration that lets the ingestor choose which dataset table to run against.
	 *
	 * @param useCaseXml path to the use case XML definition file
	 * @param rdfDefinitions RDF/OWL files used to resolve policy/test metadata
	 * @param datasetPath path to the dataset input
	 * @param useCaseId optional use case identifier
	 * @param implementationPackages Java packages to scan for test implementations
	 * @param threadCount number of worker threads to use
	 * @param dedupEnabled whether distinct-value execution is enabled
	 * @param recordFilter pre-execution record filter criteria
	 */
	public AppConfig(
			Path useCaseXml,
			List<Path> rdfDefinitions,
			Path datasetPath,
			String useCaseId,
			List<String> implementationPackages,
			int threadCount,
			boolean dedupEnabled,
			RecordFilterSpec recordFilter) {
		this(useCaseXml, rdfDefinitions, datasetPath, useCaseId, implementationPackages, threadCount, dedupEnabled,
				recordFilter, "", "");
	}

	/**
	 * Creates a configuration with explicit table selection and no dataset view.
	 */
	public AppConfig(
			Path useCaseXml,
			List<Path> rdfDefinitions,
			Path datasetPath,
			String useCaseId,
			List<String> implementationPackages,
			int threadCount,
			boolean dedupEnabled,
			RecordFilterSpec recordFilter,
			String datasetTable) {
		this(useCaseXml, rdfDefinitions, datasetPath, useCaseId, implementationPackages, threadCount, dedupEnabled,
				recordFilter, datasetTable, "");
	}

	/**
	 * Canonical constructor; substitutes an empty record filter and table selection, and the
	 * default execution policy, when none is supplied.
	 */
	public AppConfig {
		recordFilter = recordFilter == null ? RecordFilterSpec.empty() : recordFilter;
		datasetTable = datasetTable == null ? "" : datasetTable.trim();
		datasetView = datasetView == null ? "" : datasetView.trim();
		datasetJoinPolicies = Collections.unmodifiableMap(new LinkedHashMap<>(
				datasetJoinPolicies == null ? Map.of() : datasetJoinPolicies));
		executionPolicy = executionPolicy == null ? ExecutionPolicy.defaults() : executionPolicy;
	}

	/**
	 * Returns a copy of this configuration with a different use case.
	 *
	 * @param newUseCaseId the use case identifier
	 * @return the copy
	 */
	public AppConfig withUseCaseId(String newUseCaseId) {
		return new AppConfig(useCaseXml, rdfDefinitions, datasetPath, newUseCaseId, implementationPackages, threadCount,
				dedupEnabled, recordFilter, datasetTable, datasetView, datasetJoinPolicies, executionPolicy);
	}

	/**
	 * Returns a copy of this configuration with a different execution policy.
	 *
	 * @param newExecutionPolicy the execution policy, or {@code null} for the defaults
	 * @return the copy
	 */
	public AppConfig withExecutionPolicy(ExecutionPolicy newExecutionPolicy) {
		return new AppConfig(useCaseXml, rdfDefinitions, datasetPath, useCaseId, implementationPackages, threadCount,
				dedupEnabled, recordFilter, datasetTable, datasetView, datasetJoinPolicies, newExecutionPolicy);
	}
}
