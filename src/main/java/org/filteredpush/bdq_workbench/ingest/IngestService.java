/** IngestService.java
 *
 * Service contract for ingesting Darwin Core input (archives or data packages) into canonical records.
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
import org.filteredpush.bdq_workbench.model.RecordDataset;

/**
 * Ingests Darwin Core input into canonical records.
 *
 * <p>Implemented by {@link DefaultIngestService}, which dispatches to a format-specific ingestor
 * such as {@link DwcArchiveIngestor} or {@link DataPackageIngestor}. Used by
 * {@link org.filteredpush.bdq_workbench.app.WorkbenchFacade#prepare(org.filteredpush.bdq_workbench.app.AppConfig)}
 * to load the dataset a run will execute tests against.
 */
public interface IngestService {

    /**
     * Ingests the dataset at the given input path.
     *
     * @param inputPath path to the dataset input file
     * @return the ingested dataset of canonical records
     */
    RecordDataset ingest(Path inputPath);
}
