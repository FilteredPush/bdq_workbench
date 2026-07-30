package org.filteredpush.bdq_workbench.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Properties;

/** Loads configuration from classpath defaults and command line overrides. */
public class ConfigLoader {

    public AppConfig load(Map<String, String> overrides) {
        Properties defaults = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                defaults.load(in);
            }
        } catch (IOException e) {
            throw new AppException("Unable to load application.properties", e);
        }
        String rdfRaw = getValue(defaults, overrides, "bdq.rdf.files", "bdqtest.ttl,bdqffdq.owl");
        List<Path> rdfFiles = Arrays.stream(rdfRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Path::of)
                .toList();

        List<String> implPackages = Arrays.stream(getValue(defaults, overrides, "bdq.discovery.packages", "org.filteredpush.qc")
                        .split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return new AppConfig(
                Path.of(getValue(defaults, overrides, "bdq.usecase.file", "bdquc.xml")),
                rdfFiles,
                Path.of(getValue(defaults, overrides, "bdq.dataset", "dataset.zip")),
                getValue(defaults, overrides, "bdq.usecase.id", ""),
                implPackages,
                parseThreadCount(getValue(defaults, overrides, "bdq.threads", "4")));
    }

    private static int parseThreadCount(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new AppException("Invalid thread count: bdq.threads must be a whole number", e);
        }
    }

    private static String getValue(Properties defaults, Map<String, String> overrides, String key, String fallback) {
        return overrides.getOrDefault(key, defaults.getProperty(key, fallback));
    }
}
