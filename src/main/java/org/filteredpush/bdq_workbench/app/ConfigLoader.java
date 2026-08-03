/** ConfigLoader.java
 *
 * Loads {@link AppConfig} values from classpath defaults (application.properties) merged with
 * command line/GUI overrides.
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
package org.filteredpush.bdq_workbench.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Properties;

/**
 * Loads configuration from classpath defaults and command line overrides.
 *
 * <p>Reads {@code application.properties} from the classpath (if present) to supply defaults
 * for each setting, then layers the caller-supplied {@code overrides} on top (keyed by the
 * same property names, e.g. {@code bdq.dataset}, {@code bdq.threads}), falling back to a
 * built-in default for any value present in neither. Used by both
 * {@link BdqWorkbenchApplication} (CLI overrides parsed from arguments) and the GUI (overrides
 * from form fields).
 */
public class ConfigLoader {

    /**
     * Builds an {@link AppConfig} from classpath defaults and the given overrides.
     *
     * @param overrides property-name-keyed override values (e.g. from CLI arguments or GUI
     *     fields) that take precedence over {@code application.properties} defaults
     * @return the resolved application configuration
     * @throws AppException if {@code application.properties} exists but cannot be read, or if
     *     {@code bdq.threads} is not a whole number
     */
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

    /**
     * Parses a thread count string.
     *
     * @param raw the raw {@code bdq.threads} value
     * @return the parsed thread count
     * @throws AppException if {@code raw} is not a valid integer
     */
    private static int parseThreadCount(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new AppException("Invalid thread count: bdq.threads must be a whole number", e);
        }
    }

    /**
     * Resolves a single setting: override value, else classpath default, else {@code fallback}.
     *
     * @param defaults properties loaded from {@code application.properties}
     * @param overrides caller-supplied override values
     * @param key the property name to resolve
     * @param fallback value to use if {@code key} is present in neither {@code overrides} nor
     *     {@code defaults}
     * @return the resolved value
     */
    private static String getValue(Properties defaults, Map<String, String> overrides, String key, String fallback) {
        return overrides.getOrDefault(key, defaults.getProperty(key, fallback));
    }
}
