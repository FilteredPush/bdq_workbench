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
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.RecordDataset;

/**
 * Dispatches ingestion based on source format.
 *
 * <p>Inspects the input path's file name and delegates to {@link DwcArchiveIngestor} for
 * {@code .zip} Darwin Core Archives or {@link DataPackageIngestor} for {@code .json}/
 * {@code datapackage} Darwin Core Data Packages.
 */
public class DefaultIngestService implements IngestService {
    private final DwcArchiveIngestor dwcArchiveIngestor;
    private final DataPackageIngestor dataPackageIngestor;

    /**
     * Creates a service with default {@link DwcArchiveIngestor} and {@link DataPackageIngestor}
     * instances.
     */
    public DefaultIngestService() {
        this(new DwcArchiveIngestor(), new DataPackageIngestor());
    }

    /**
     * Creates a service wired to the given ingestors.
     *
     * @param dwcArchiveIngestor ingestor used for {@code .zip} Darwin Core Archive inputs
     * @param dataPackageIngestor ingestor used for {@code .json}/{@code datapackage} inputs
     */
    public DefaultIngestService(DwcArchiveIngestor dwcArchiveIngestor, DataPackageIngestor dataPackageIngestor) {
        this.dwcArchiveIngestor = dwcArchiveIngestor;
        this.dataPackageIngestor = dataPackageIngestor;
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
        String fileName = inputPath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".zip")) {
            return dwcArchiveIngestor.ingest(inputPath);
        }
        if (fileName.endsWith(".json") || fileName.endsWith("datapackage")) {
            return dataPackageIngestor.ingest(inputPath);
        }
        throw new AppException("Unsupported dataset input: " + inputPath);
    }
}
