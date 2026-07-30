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

/** Ingests Darwin Core Data Packages into canonical records. */
public class DataPackageIngestor {
    private final ObjectMapper mapper = new ObjectMapper();

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
