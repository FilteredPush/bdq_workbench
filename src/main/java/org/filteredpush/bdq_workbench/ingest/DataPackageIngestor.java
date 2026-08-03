/** DataPackageIngestor.java
 *
 * Ingests Frictionless-style Darwin Core Data Packages (a datapackage.json manifest referencing a CSV resource) into canonical records.
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.RecordDataset;

/**
 * Ingests Darwin Core Data Packages into canonical records.
 *
 * <p>Reads the {@code datapackage.json} manifest at the given path, resolves its first declared
 * resource to a sibling CSV file, and parses that CSV into a {@link RecordDataset}. Each row's
 * record ID is taken from an {@code id} or {@code occurrenceID} column, falling back to a
 * synthesized {@code row-<n>} identifier.
 */
public class DataPackageIngestor {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Ingests a Darwin Core Data Package into canonical records.
     *
     * @param dataPackagePath path to the {@code datapackage.json} manifest file
     * @return the dataset parsed from the manifest's first resource
     * @throws AppException if the manifest has no resources, or the resource file cannot be read
     */
    public RecordDataset ingest(Path dataPackagePath) {
        try {
            JsonNode root = mapper.readTree(Files.newBufferedReader(dataPackagePath));
            JsonNode resources = root.path("resources");
            if (!resources.isArray() || resources.isEmpty()) {
                throw new AppException("Data package does not include resources");
            }
            JsonNode firstResource = resources.get(0);
            Path dataPath = dataPackagePath.getParent().resolve(firstResource.path("path").asText()).normalize();
            return parseCsv(dataPath);
        } catch (IOException e) {
            throw new AppException("Failed to ingest Darwin Core Data Package from " + dataPackagePath, e);
        }
    }

    /**
     * Parses a comma-delimited CSV file into canonical records, one per row.
     *
     * @param csvPath path to the CSV resource file, with a header row
     * @return the dataset parsed from the CSV
     * @throws IOException if the file cannot be read
     */
    private RecordDataset parseCsv(Path csvPath) throws IOException {
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(Files.newBufferedReader(csvPath))) {
            List<CanonicalRecord> records = new ArrayList<>();
            parser.forEach(row -> {
                Map<String, String> values = new HashMap<>();
                row.toMap().forEach((k, v) -> values.put(k == null ? "" : k.trim(), v == null ? "" : v.trim()));
                String id = values.getOrDefault("id", values.getOrDefault("occurrenceID", "row-" + row.getRecordNumber()));
                records.add(new CanonicalRecord(id, values));
            });
            return new RecordDataset(records);
        }
    }
}
