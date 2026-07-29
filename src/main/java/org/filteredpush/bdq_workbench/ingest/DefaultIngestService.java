package org.filteredpush.bdq_workbench.ingest;

import java.nio.file.Path;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.RecordDataset;

/** Dispatches ingestion based on source format. */
public class DefaultIngestService implements IngestService {
    private final DwcArchiveIngestor dwcArchiveIngestor;
    private final DataPackageIngestor dataPackageIngestor;

    public DefaultIngestService() {
        this(new DwcArchiveIngestor(), new DataPackageIngestor());
    }

    public DefaultIngestService(DwcArchiveIngestor dwcArchiveIngestor, DataPackageIngestor dataPackageIngestor) {
        this.dwcArchiveIngestor = dwcArchiveIngestor;
        this.dataPackageIngestor = dataPackageIngestor;
    }

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
