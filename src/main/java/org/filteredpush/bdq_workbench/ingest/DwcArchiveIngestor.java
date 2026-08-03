/** DwcArchiveIngestor.java
 *
 * Ingests Darwin Core Archives (zipped, tab-delimited core data files) into canonical records.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.RecordDataset;

/**
 * Ingests Darwin Core Archives into canonical records.
 *
 * <p>Opens the zip archive, locates its core data file (preferring {@code occurrence.txt}, else
 * the first {@code .txt} entry found), and parses it as tab-delimited text into a
 * {@link RecordDataset}. Each row's record ID is taken from an {@code id} or {@code occurrenceID}
 * column, falling back to a synthesized {@code row-<n>} identifier.
 */
public class DwcArchiveIngestor {

    /**
     * Ingests a Darwin Core Archive into canonical records.
     *
     * @param archivePath path to the zipped Darwin Core Archive
     * @return the dataset parsed from the archive's core data file
     * @throws AppException if the archive cannot be read or has no core data text file
     */
    public RecordDataset ingest(Path archivePath) {
        try (ZipFile zipFile = new ZipFile(archivePath.toFile())) {
            ZipEntry core = resolveCoreDataEntry(zipFile);
            try (InputStream in = zipFile.getInputStream(core);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    CSVParser parser = CSVFormat.TDF.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
                List<CanonicalRecord> records = new ArrayList<>();
                parser.forEach(row -> {
                    Map<String, String> values = new HashMap<>();
                    row.toMap().forEach((k, v) -> values.put(k == null ? "" : k.trim(), v == null ? "" : v.trim()));
                    String id = values.getOrDefault("id", values.getOrDefault("occurrenceID", "row-" + row.getRecordNumber()));
                    records.add(new CanonicalRecord(id, values));
                });
                return new RecordDataset(records);
            }
        } catch (IOException e) {
            throw new AppException("Failed to ingest DwC-A from " + archivePath, e);
        }
    }

    /**
     * Locates the archive's core data entry, preferring {@code occurrence.txt} and otherwise
     * falling back to the first non-directory {@code .txt} entry found.
     *
     * @param zipFile the open archive to search
     * @return the core data zip entry
     * @throws AppException if no {@code .txt} entry is found in the archive
     */
    private ZipEntry resolveCoreDataEntry(ZipFile zipFile) {
        ZipEntry occurrence = zipFile.getEntry("occurrence.txt");
        if (occurrence != null) {
            return occurrence;
        }
        return zipFile.stream()
                .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".txt"))
                .findFirst()
                .orElseThrow(() -> new AppException("No core data text file found in archive"));
    }
}
