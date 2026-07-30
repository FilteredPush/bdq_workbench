package org.filteredpush.bdq_workbench.app;

import java.nio.file.Path;
import java.util.List;

/** Immutable application configuration values. */
public record AppConfig(
        Path useCaseXml,
        List<Path> rdfDefinitions,
        Path datasetPath,
        String useCaseId,
        List<String> implementationPackages,
        int threadCount) {
}
