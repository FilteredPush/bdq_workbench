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
import org.filteredpush.bdq_workbench.model.RecordFilterSpec;

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
        return load(overrides, null);
    }

    /**
     * Builds an {@link AppConfig} for a command line run, filling in the same default resource
     * sources the GUI uses and fetching any remote ones.
     *
     * <p>{@link #load(Map)} treats the use case, test definition and ontology settings as literal
     * file paths, because the GUI resolves its own source fields before handing them over. A
     * command line run has nobody to do that for it, so this variant applies
     * {@link WorkbenchDefaults}' published sources wherever a setting is blank and resolves any
     * HTTP source through {@code resolver}. That is what makes {@code --dataset} the only
     * argument a run needs.
     *
     * @param overrides property-name-keyed override values parsed from command line arguments
     * @param resolver resolver used to fetch and cache remote sources
     * @return the resolved application configuration, with every resource source a local file
     * @throws AppException if a setting is malformed or a remote source cannot be fetched
     */
    public AppConfig loadForCliRun(Map<String, String> overrides, CachedResourceResolver resolver) {
        return load(overrides, resolver);
    }

    /**
     * Builds an {@link AppConfig} from classpath defaults and the given overrides.
     *
     * @param overrides property-name-keyed override values
     * @param resolver resolver used to fetch remote resource sources and to supply the default
     *     sources, or {@code null} to treat every source setting as a literal local path
     * @return the resolved application configuration
     * @throws AppException if {@code application.properties} exists but cannot be read, or if a
     *     setting is malformed
     */
    private AppConfig load(Map<String, String> overrides, CachedResourceResolver resolver) {
        Properties defaults = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                defaults.load(in);
            }
        } catch (IOException e) {
            throw new AppException("Unable to load application.properties", e);
        }
        String rdfRaw = getValue(defaults, overrides, "bdq.rdf.files", "bdqtest.ttl,bdqffdq.owl");
        if (resolver != null && rdfRaw.isBlank()) {
            rdfRaw = WorkbenchDefaults.TEST_DEFINITIONS_SOURCE + "," + WorkbenchDefaults.ONTOLOGY_SOURCE;
        }
        List<Path> rdfFiles = Arrays.stream(rdfRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(source -> resolveSource(source, resolver))
                .toList();

        List<String> implPackages = Arrays.stream(getValue(defaults, overrides, "bdq.discovery.packages", "org.filteredpush.qc")
                        .split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return new AppConfig(
                resolveSource(
                        orDefaultSource(
                                getValue(defaults, overrides, "bdq.usecase.file", "bdquc.xml"),
                                WorkbenchDefaults.USE_CASE_SOURCE,
                                resolver),
                        resolver),
                rdfFiles,
                Path.of(getValue(defaults, overrides, "bdq.dataset", "dataset.zip")),
                getValue(defaults, overrides, "bdq.usecase.id", ""),
                implPackages,
                parseThreadCount(getValue(defaults, overrides, "bdq.threads", "4")),
                parseBoolean(getValue(defaults, overrides, "bdq.execution.dedup", "true"), "bdq.execution.dedup"),
                RecordFilterSpec.parse(getValue(defaults, overrides, "bdq.record.filters", "")),
                getValue(defaults, overrides, "bdq.dataset.table", ""),
                getValue(defaults, overrides, "bdq.dataset.view", ""));
    }

    /**
     * Resolves one resource source setting to a local file.
     *
     * @param source the configured source, a local path or an HTTP URL
     * @param resolver resolver used to fetch remote sources, or {@code null} to treat every
     *     source as a literal local path
     * @return the local path of the resource
     */
    private static Path resolveSource(String source, CachedResourceResolver resolver) {
        if (resolver != null && CachedResourceResolver.isRemote(source)) {
            return resolver.resolveSource(source.trim());
        }
        return Path.of(source);
    }

    /**
     * Substitutes a default source for a blank setting, for command line runs.
     *
     * @param configured the configured value
     * @param defaultSource the default source to use when {@code configured} is blank
     * @param resolver non-null for a command line run, {@code null} otherwise
     * @return the source to resolve
     */
    private static String orDefaultSource(String configured, String defaultSource,
            CachedResourceResolver resolver) {
        return resolver != null && configured.isBlank() ? defaultSource : configured;
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
     * Parses a strict {@code "true"}/{@code "false"} boolean setting.
     *
     * @param raw the raw setting value
     * @param propertyName the property name, used in the error message if {@code raw} is invalid
     * @return the parsed boolean
     * @throws AppException if {@code raw} is neither {@code "true"} nor {@code "false"}
     *     (case-insensitive)
     */
    private static boolean parseBoolean(String raw, String propertyName) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new AppException("Invalid value for " + propertyName + ": must be true or false");
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
