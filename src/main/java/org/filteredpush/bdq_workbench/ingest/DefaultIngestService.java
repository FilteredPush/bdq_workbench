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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches ingestion based on source format.
 *
 * <p>Inspects the input path and delegates to {@link DwcArchiveIngestor} for Darwin Core
 * Archives, and to {@link DataPackageIngestor} for data package manifests
 * ({@code .json}/{@code datapackage}) and zipped data packages containing
 * {@code datapackage.json}.
 */
public class DefaultIngestService implements IngestService {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultIngestService.class);
    private final DwcArchiveIngestor dwcArchiveIngestor;
    private final DataPackageIngestor dataPackageIngestor;
    private final RelationalDatasetIngestor relationalDatasetIngestor;
    private final DatasetViewIO datasetViewIO;
    private final ViewFlattener viewFlattener;

    /**
     * Creates a service with default {@link DwcArchiveIngestor} and {@link DataPackageIngestor}
     * instances.
     */
    public DefaultIngestService() {
        this(new DwcArchiveIngestor(), new DataPackageIngestor(),
        		new RelationalDatasetIngestor(), new DatasetViewIO(), new ViewFlattener());
    }

    /**
     * Creates a service wired to the given ingestors.
     *
     * @param dwcArchiveIngestor ingestor used for {@code .zip} Darwin Core Archive inputs
     * @param dataPackageIngestor ingestor used for {@code .json}/{@code datapackage} inputs
     */
    public DefaultIngestService(DwcArchiveIngestor dwcArchiveIngestor, DataPackageIngestor dataPackageIngestor) {
        this(dwcArchiveIngestor, dataPackageIngestor,
        		new RelationalDatasetIngestor(), new DatasetViewIO(), new ViewFlattener());
    }

    /**
     * Creates a service wired to explicit flat and relational ingestion components.
     */
    public DefaultIngestService(
        	DwcArchiveIngestor dwcArchiveIngestor,
        	DataPackageIngestor dataPackageIngestor,
        	RelationalDatasetIngestor relationalDatasetIngestor,
        	DatasetViewIO datasetViewIO,
        	ViewFlattener viewFlattener) {
        this.dwcArchiveIngestor = dwcArchiveIngestor;
        this.dataPackageIngestor = dataPackageIngestor;
        this.relationalDatasetIngestor = relationalDatasetIngestor;
        this.datasetViewIO = datasetViewIO;
        this.viewFlattener = viewFlattener;
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
        if (datasetView != null && !datasetView.isBlank()) {
            return ingestThroughView(inputPath, requestedTable, datasetView);
        }
        return ingestWithOptionalBuiltInView(inputPath, requestedTable);
    }

    private RecordDataset ingestFlat(Path inputPath, String requestedTable) {
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
        RelationalIngestResult relational = relationalDatasetIngestor.ingest(inputPath, requestedTable);
        DatasetView view = datasetViewIO.load(Path.of(datasetViewPath));
        datasetViewIO.validateCompatibility(view, relational.schema());
        ViewFlattenResult flattened = viewFlattener.flatten(relational, view);
        logDiagnostics(relational.diagnostics(), flattened.diagnostics());
        return flattened.dataset();
    }

    private RecordDataset ingestWithOptionalBuiltInView(Path inputPath, String requestedTable) {
        RelationalIngestResult relational = relationalDatasetIngestor.ingest(inputPath, requestedTable);
        if (relational.graphs().isEmpty()) {
        	return ingestFlat(inputPath, requestedTable);
        }
        List<String> diagnostics = new ArrayList<>();
        Optional<DatasetView> builtIn = BuiltInDatasetViews.select(relational.schema(), diagnostics);
        if (builtIn.isEmpty()) {
        	logDiagnostics(relational.diagnostics(), diagnostics);
        	List<org.filteredpush.bdq_workbench.model.CanonicalRecord> rows = relational.graphs().stream()
        			.map(org.filteredpush.bdq_workbench.model.RecordGraph::core)
        			.toList();
            return new RecordDataset(rows, relational.graphs());
        }
        ViewFlattenResult flattened = viewFlattener.flatten(relational, builtIn.get());
        logDiagnostics(relational.diagnostics(), diagnostics, flattened.diagnostics());
        return flattened.dataset();
    }

    private void logDiagnostics(List<String>... groups) {
        for (List<String> group : groups) {
        	group.forEach(message -> LOG.warn("Dataset view diagnostic: {}", message));
        }
    }
}
