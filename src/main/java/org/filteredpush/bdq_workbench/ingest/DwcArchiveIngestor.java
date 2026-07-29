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

/** Ingests Darwin Core Archives into canonical records. */
public class DwcArchiveIngestor {

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
