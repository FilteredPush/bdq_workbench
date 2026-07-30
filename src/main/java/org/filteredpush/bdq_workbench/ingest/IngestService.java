package org.filteredpush.bdq_workbench.ingest;

import java.nio.file.Path;
import org.filteredpush.bdq_workbench.model.RecordDataset;

/** Ingests Darwin Core input into canonical records. */
public interface IngestService {
    RecordDataset ingest(Path inputPath);
}
