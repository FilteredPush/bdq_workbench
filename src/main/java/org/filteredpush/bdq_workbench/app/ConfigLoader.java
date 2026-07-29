package org.filteredpush.bdq_workbench.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Loads configuration from classpath defaults and system properties. */
public class ConfigLoader {

    public AppConfig load() {
        Properties defaults = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                defaults.load(in);
            }
        } catch (IOException e) {
            throw new AppException("Unable to load application.properties", e);
        }
        String rdfRaw = getValue(defaults, "bdq.rdf.files", "bdqtest.ttl,bdqffdq.owl");
        List<Path> rdfFiles = Arrays.stream(rdfRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Path::of)
                .toList();

        List<String> implPackages = Arrays.stream(getValue(defaults, "bdq.discovery.packages", "org.filteredpush")
                        .split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return new AppConfig(
                Path.of(getValue(defaults, "bdq.usecase.file", "bdquc.xml")),
                rdfFiles,
                Path.of(getValue(defaults, "bdq.dataset", "dataset.zip")),
                getValue(defaults, "bdq.usecase.id", ""),
                implPackages,
                Integer.parseInt(getValue(defaults, "bdq.threads", "4")));
    }

    private static String getValue(Properties defaults, String key, String fallback) {
        return System.getProperty(key, defaults.getProperty(key, fallback));
    }
}
