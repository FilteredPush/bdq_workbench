/** DefaultIngestService.java
 *
 * Default IngestService implementation that dispatches to a DwcArchiveIngestor or DataPackageIngestor based on the input file's extension.
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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.SyntheticDataMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches ingestion based on source format, building a dataset view for multi-table inputs.
 *
 * <p>Inspects the input path and delegates to {@link DwcArchiveIngestor} for Darwin Core
 * Archives, and to {@link DataPackageIngestor} for data package manifests
 * ({@code .json}/{@code datapackage}) and zipped data packages containing
 * {@code datapackage.json}. A dataset with a single table (or whose chosen table has no related
 * tables) is read flat. Otherwise the tests run over a dataset view: the one supplied
 * ({@code --dataset-view}), or one built by {@link AutomaticDatasetViews}, which maps every column
 * and needs a multiplicity-handling decision for each related table with more than one row per
 * grain record, taken from the supplied join policies or asked of the {@link JoinPolicyResolver}.
 */
public class DefaultIngestService implements IngestService {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultIngestService.class);
    private final DwcArchiveIngestor dwcArchiveIngestor;
    private final DataPackageIngestor dataPackageIngestor;
    private final RelationalDatasetIngestor relationalDatasetIngestor;
    private final DatasetViewIO datasetViewIO;
    private final ViewFlattener viewFlattener;
    private final JoinPolicyResolver joinPolicyResolver;

    /**
     * Creates a service with default {@link DwcArchiveIngestor} and {@link DataPackageIngestor}
     * instances.
     */
    public DefaultIngestService() {
        this(JoinPolicyResolver.NONE);
    }

    /**
     * Creates a service with default ingestors that asks {@code joinPolicyResolver} for any
     * multiplicity decisions missing when a multi-table dataset is run without a view.
     *
     * @param joinPolicyResolver resolver for undecided join policies
     */
    public DefaultIngestService(JoinPolicyResolver joinPolicyResolver) {
        this(new DwcArchiveIngestor(), new DataPackageIngestor(),
                new RelationalDatasetIngestor(), new DatasetViewIO(), new ViewFlattener(), joinPolicyResolver);
    }

    /**
     * Creates a service wired to the given ingestors.
     *
     * @param dwcArchiveIngestor ingestor used for {@code .zip} Darwin Core Archive inputs
     * @param dataPackageIngestor ingestor used for {@code .json}/{@code datapackage} inputs
     */
    public DefaultIngestService(DwcArchiveIngestor dwcArchiveIngestor, DataPackageIngestor dataPackageIngestor) {
        this(dwcArchiveIngestor, dataPackageIngestor,
                new RelationalDatasetIngestor(), new DatasetViewIO(), new ViewFlattener(), JoinPolicyResolver.NONE);
    }

    /**
     * Creates a service wired to explicit flat and relational ingestion components.
     *
     * @param dwcArchiveIngestor flat Darwin Core Archive ingestor
     * @param dataPackageIngestor flat data package ingestor
     * @param relationalDatasetIngestor relational ingestor used for multi-table inputs
     * @param datasetViewIO dataset-view file reader
     * @param viewFlattener applies dataset views
     * @param joinPolicyResolver resolver for undecided join policies
     */
    public DefaultIngestService(
        	DwcArchiveIngestor dwcArchiveIngestor,
        	DataPackageIngestor dataPackageIngestor,
        	RelationalDatasetIngestor relationalDatasetIngestor,
        	DatasetViewIO datasetViewIO,
            ViewFlattener viewFlattener,
            JoinPolicyResolver joinPolicyResolver) {
        this.dwcArchiveIngestor = dwcArchiveIngestor;
        this.dataPackageIngestor = dataPackageIngestor;
        this.relationalDatasetIngestor = relationalDatasetIngestor;
        this.datasetViewIO = datasetViewIO;
        this.viewFlattener = viewFlattener;
        this.joinPolicyResolver = joinPolicyResolver == null ? JoinPolicyResolver.NONE : joinPolicyResolver;
    }

    /**
     * Ingests the given input path, dispatching to the appropriate ingestor based on its file
     * extension.
     *
     * @param inputPath path to the dataset input, a {@code .zip} archive or a
     *     {@code .json}/{@code datapackage} manifest
     * @return the ingested dataset
     * @throws AppException if the input's file extension does not match a supported format
     */
    @Override
    public RecordDataset ingest(Path inputPath) {
        return ingest(inputPath, "");
    }

    /**
     * Ingests the given input path, dispatching to the appropriate ingestor based on its file
     * extension and passing on the caller's choice of table.
     *
     * @param inputPath path to the dataset input, a {@code .zip} archive or a
     *     {@code .json}/{@code datapackage} manifest
     * @param requestedTable the name, file name or Darwin Core row type of the table to read;
     *     blank to let the ingestor choose
     * @return the ingested dataset
     * @throws AppException if the input's file extension does not match a supported format
     */
    @Override
    public RecordDataset ingest(Path inputPath, String requestedTable) {
        return ingest(inputPath, requestedTable, "");
    }

    @Override
    public RecordDataset ingest(Path inputPath, String requestedTable, String datasetView) {
        return ingest(inputPath, requestedTable, datasetView, Map.of());
    }

    /**
     * Ingests the dataset through the supplied dataset view or, for a multi-table dataset without
     * one, through an automatically built view.
     *
     * @param inputPath path to the dataset input
     * @param requestedTable the grain table; blank to let the ingestor choose
     * @param datasetView optional dataset view JSON path
     * @param joinPolicies multiplicity-handling policies by related table, used for the automatic
     *     view
     * @return the ingested dataset
     * @throws DatasetViewRequiredException if the automatic view needs a decision no one gave
     */
    @Override
    public RecordDataset ingest(
            Path inputPath,
            String requestedTable,
            String datasetView,
            Map<String, DatasetViewCardinalityPolicy> joinPolicies) {
        if (datasetView != null && !datasetView.isBlank()) {
            if (joinPolicies != null && !joinPolicies.isEmpty()) {
                LOG.warn("Ignoring join policies {}: the dataset view {} defines its own", joinPolicies.keySet(), datasetView);
            }
            return ingestThroughView(inputPath, requestedTable, datasetView);
        }
        return ingestWithAutomaticView(inputPath, requestedTable, joinPolicies == null ? Map.of() : joinPolicies);
    }

    /**
     * Ingests one table directly with the format-specific flat ingestor, recording it as a
     * single-table input.
     *
     * @param inputPath path to the dataset input
     * @param requestedTable the requested table; blank to let the ingestor choose
     * @return the flat dataset, carrying a single-table input description
     */
    private RecordDataset ingestFlat(Path inputPath, String requestedTable) {
        RecordDataset dataset = readFlat(inputPath, requestedTable);
        String tableName = requestedTable == null || requestedTable.isBlank()
                ? inputPath.getFileName().toString()
                : requestedTable;
        return withMarkers(dataset.withInputDescription(DatasetInputDescriber.singleTable(tableName, dataset)),
                SyntheticDataDetector.scan(dataset));
    }

    /**
     * Attaches a synthetic-data scan, and the terms gathered to name each record from its related
     * rows, to a relationally ingested dataset's input description.
     *
     * @param dataset the ingested dataset
     * @param markers the scan of its raw input rows
     * @param relational the relational rows the dataset was built from
     * @return the dataset carrying the scan and the record identification
     */
    private static RecordDataset withMarkers(RecordDataset dataset, SyntheticDataMarkers markers,
            RelationalIngestResult relational) {
        RecordDataset marked = withMarkers(dataset, markers);
        return marked.withInputDescription(marked.inputDescription().withRecordIdentification(
                RecordIdentificationCollector.collect(relational)));
    }

    /**
     * Attaches a synthetic-data scan to a dataset's input description.
     *
     * @param dataset the ingested dataset
     * @param markers the scan of its raw input rows
     * @return the dataset carrying the scan
     */
    private static RecordDataset withMarkers(RecordDataset dataset, SyntheticDataMarkers markers) {
        if (markers.found()) {
            LOG.warn("Input data: {}", markers.summaryLine());
        }
        return dataset.withInputDescription(dataset.inputDescription().withSyntheticMarkers(markers));
    }

    /**
     * Dispatches flat ingestion to the ingestor matching the input's format.
     *
     * @param inputPath path to the dataset input
     * @param requestedTable the requested table; blank to let the ingestor choose
     * @return the flat dataset
     * @throws AppException if the input's file extension does not match a supported format
     */
    private RecordDataset readFlat(Path inputPath, String requestedTable) {
        String fileName = inputPath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".zip")) {
            if (DataPackageArchiveSupport.isDataPackageArchive(inputPath)) {
                return dataPackageIngestor.ingest(inputPath, requestedTable);
            }
            return dwcArchiveIngestor.ingest(inputPath, requestedTable);
        }
        if (fileName.endsWith(".json") || fileName.endsWith("datapackage")) {
            return dataPackageIngestor.ingest(inputPath, requestedTable);
        }
        throw new AppException("Unsupported dataset input: " + inputPath);
    }

    private RecordDataset ingestThroughView(Path inputPath, String requestedTable, String datasetViewPath) {
        DatasetView view = datasetViewIO.load(Path.of(datasetViewPath));
        String effectiveTable = view.grainTable().isBlank() ? requestedTable : view.grainTable();
        RelationalIngestResult relational = relationalDatasetIngestor.ingest(inputPath, effectiveTable);
        datasetViewIO.validateCompatibility(view, relational.schema());
        ViewFlattenResult flattened = viewFlattener.flatten(relational, view);
        logDiagnostics(relational.diagnostics(), flattened.diagnostics());
        return withMarkers(flattened.dataset().withInputDescription(DatasetInputDescriber.flattened(
                relational,
                view,
                "dataset view file " + datasetViewPath,
                flattened.dataset().records().size())), SyntheticDataDetector.scanGraphs(relational.graphs()), relational);
    }

    /**
     * Ingests a dataset without a view file: flat when the chosen table has no related tables,
     * otherwise through an automatically built view.
     *
     * @param inputPath path to the dataset input
     * @param requestedTable the grain table; blank to let the ingestor choose
     * @param joinPolicies multiplicity-handling policies by related table
     * @return the ingested dataset
     */
    private RecordDataset ingestWithAutomaticView(
            Path inputPath,
            String requestedTable,
            Map<String, DatasetViewCardinalityPolicy> joinPolicies) {
        RelationalIngestResult relational = relationalDatasetIngestor.ingest(inputPath, requestedTable);
        if (relational.graphs().isEmpty()) {
        	return ingestFlat(inputPath, requestedTable);
        }
        boolean hasRelatedTables = !new DatasetViewSuggester()
                .joinCandidates(relational.schema(), relational.coreTable())
                .isEmpty();
        if (!hasRelatedTables) {
            if (!joinPolicies.isEmpty()) {
                LOG.warn("Ignoring join policies {}: table {} has no related tables", joinPolicies.keySet(),
                        relational.coreTable());
            }
            List<CanonicalRecord> rows = relational.graphs().stream().map(RecordGraph::core).toList();
            return withMarkers(new RecordDataset(rows, List.of(), DatasetInputDescriber.structured(relational, "")),
                    SyntheticDataDetector.scanGraphs(relational.graphs()), relational);
        }
        DatasetView view = AutomaticDatasetViews.build(relational, joinPolicies, joinPolicyResolver);
        LOG.info("Built dataset view over grain table {} with joins {}", view.grainTable(),
                AutomaticDatasetViews.describePolicies(view));
        ViewFlattenResult flattened = viewFlattener.flatten(relational, view);
        logDiagnostics(relational.diagnostics(), flattened.diagnostics());
        return withMarkers(flattened.dataset().withInputDescription(DatasetInputDescriber.flattened(
                relational,
                view,
                "automatic dataset view (" + AutomaticDatasetViews.describePolicies(view) + ")",
                flattened.dataset().records().size())), SyntheticDataDetector.scanGraphs(relational.graphs()), relational);
    }

    private void logDiagnostics(List<String>... groups) {
        for (List<String> group : groups) {
        	group.forEach(message -> LOG.warn("Dataset view diagnostic: {}", message));
        }
    }
}
