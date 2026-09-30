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
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import org.filteredpush.bdq_workbench.execution.ExecutionPolicy;
import org.filteredpush.bdq_workbench.execution.ResourceClass;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
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
                getValue(defaults, overrides, "bdq.dataset.view", ""),
                parseJoinPolicies(getValue(defaults, overrides, "bdq.dataset.join.policies", "")),
                parseExecutionPolicy(key -> getValue(defaults, overrides, key, null)));
    }

    /**
     * Builds the {@link ExecutionPolicy} from the {@code bdq.execution.*} settings; any setting
     * that is absent or blank keeps its {@link ExecutionPolicy#defaults() default}.
     *
     * <ul>
     *   <li>{@code bdq.execution.lanes} ({@code true}/{@code false}) — per-resource concurrency
     *   lanes</li>
     *   <li>{@code bdq.execution.concurrency.external}, {@code .unclassified}, {@code .local} —
     *   default lane limits ({@code local} 0 means the whole worker pool)</li>
     *   <li>{@code bdq.execution.adaptive} ({@code true}/{@code false}) — adaptive throttling and
     *   circuit breaking</li>
     *   <li>{@code bdq.execution.circuit.failures} — consecutive external failures that open a
     *   lane's circuit (0 disables the circuit breaker)</li>
     *   <li>{@code bdq.execution.circuit.cooldown.ms}, {@code bdq.execution.circuit.cooldown.max.ms}
     *   — initial and maximum circuit cooldown</li>
     *   <li>{@code bdq.execution.retries} — maximum workbench retries of a transient external
     *   failure (0 disables retries)</li>
     *   <li>{@code bdq.execution.retry.delay.ms}, {@code bdq.execution.retry.delay.max.ms} — base
     *   and maximum retry backoff</li>
     *   <li>{@code bdq.execution.reuse} ({@code true}/{@code false}) — PRE-to-POST result
     *   reuse</li>
     *   <li>{@code bdq.execution.overrides} — see {@link #parseResourceOverrides(String)}</li>
     *   <li>{@code bdq.execution.lane.limits} — see {@link #parseLaneLimits(String)}</li>
     * </ul>
     *
     * @param settings looks a setting up by property name, returning {@code null} when absent
     * @return the execution policy
     * @throws AppException if a setting is malformed or out of range
     */
    static ExecutionPolicy parseExecutionPolicy(Function<String, String> settings) {
        ExecutionPolicy.Builder builder = ExecutionPolicy.builder();
        try {
            String value;
            if ((value = setting(settings, "bdq.execution.lanes")) != null) {
                builder.laneSchedulingEnabled(parseBoolean(value, "bdq.execution.lanes"));
            }
            if ((value = setting(settings, "bdq.execution.concurrency.external")) != null) {
                builder.externalConcurrency(parseInt(value, "bdq.execution.concurrency.external"));
            }
            if ((value = setting(settings, "bdq.execution.concurrency.unclassified")) != null) {
                builder.unclassifiedConcurrency(parseInt(value, "bdq.execution.concurrency.unclassified"));
            }
            if ((value = setting(settings, "bdq.execution.concurrency.local")) != null) {
                builder.localConcurrency(parseInt(value, "bdq.execution.concurrency.local"));
            }
            if ((value = setting(settings, "bdq.execution.adaptive")) != null) {
                builder.adaptiveThrottlingEnabled(parseBoolean(value, "bdq.execution.adaptive"));
            }
            if ((value = setting(settings, "bdq.execution.circuit.failures")) != null) {
                builder.circuitFailureThreshold(parseInt(value, "bdq.execution.circuit.failures"));
            }
            Duration cooldown = parseMillis(setting(settings, "bdq.execution.circuit.cooldown.ms"),
                    "bdq.execution.circuit.cooldown.ms");
            Duration maxCooldown = parseMillis(setting(settings, "bdq.execution.circuit.cooldown.max.ms"),
                    "bdq.execution.circuit.cooldown.max.ms");
            if (maxCooldown != null) {
                builder.circuitMaxCooldown(maxCooldown);
            }
            if (cooldown != null) {
                builder.circuitInitialCooldown(cooldown);
            }
            if ((value = setting(settings, "bdq.execution.retries")) != null) {
                int retries = parseInt(value, "bdq.execution.retries");
                builder.maxRetries(retries).retriesEnabled(retries > 0);
            }
            Duration retryDelay = parseMillis(setting(settings, "bdq.execution.retry.delay.ms"),
                    "bdq.execution.retry.delay.ms");
            Duration retryMaxDelay = parseMillis(setting(settings, "bdq.execution.retry.delay.max.ms"),
                    "bdq.execution.retry.delay.max.ms");
            if (retryMaxDelay != null) {
                builder.retryMaxDelay(retryMaxDelay);
            }
            if (retryDelay != null) {
                builder.retryBaseDelay(retryDelay);
            }
            if ((value = setting(settings, "bdq.execution.reuse")) != null) {
                builder.prePostReuseEnabled(parseBoolean(value, "bdq.execution.reuse"));
            }
            builder.overrides(parseResourceOverrides(setting(settings, "bdq.execution.overrides")));
            builder.resourceConcurrency(parseLaneLimits(setting(settings, "bdq.execution.lane.limits")));
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new AppException("Invalid execution setting: " + e.getMessage(), e);
        }
    }

    /**
     * Parses {@code bdq.execution.overrides}: semicolon-separated {@code target=spec} entries.
     * The target (a test ID, {@code class#method}, or implementation class) is everything before
     * the last {@code =}; the spec is a comma-separated list of any of {@code external},
     * {@code local}, {@code unclassified} (the resource class), {@code lane:NAME} (a shared lane
     * name), and {@code max:N} (a concurrency limit). For example
     * {@code org.filteredpush.qc.georeference.DwCGeoRefDQDefaults=external,lane:worms,max:1}.
     *
     * @param raw the configured value, possibly {@code null} or blank
     * @return the overrides keyed by target, in the order given
     * @throws AppException if an entry is malformed
     */
    static Map<String, ExecutionPolicy.ResourceOverride> parseResourceOverrides(String raw) {
        Map<String, ExecutionPolicy.ResourceOverride> overrides = new LinkedHashMap<>();
        for (String entry : raw == null ? new String[0] : raw.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            int separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new AppException("Invalid execution override '" + entry.trim() + "': expected target=spec");
            }
            String target = entry.substring(0, separator).trim();
            ResourceClass resourceClass = null;
            String lane = null;
            Integer max = null;
            for (String item : entry.substring(separator + 1).split(",")) {
                String token = item.trim();
                String lower = token.toLowerCase(Locale.ROOT);
                if (token.isEmpty()) {
                    continue;
                } else if (lower.startsWith("lane:")) {
                    lane = token.substring("lane:".length()).trim();
                } else if (lower.startsWith("max:")) {
                    max = parseInt(token.substring("max:".length()).trim(), "bdq.execution.overrides max");
                } else {
                    try {
                        resourceClass = ResourceClass.valueOf(token.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new AppException("Invalid execution override '" + token + "' for " + target
                                + ": expected one of external, local, unclassified, lane:NAME, max:N");
                    }
                }
            }
            try {
                overrides.put(target, new ExecutionPolicy.ResourceOverride(resourceClass, lane, max));
            } catch (IllegalArgumentException e) {
                throw new AppException("Invalid execution override for " + target + ": " + e.getMessage(), e);
            }
        }
        return overrides;
    }

    /**
     * Parses {@code bdq.execution.lane.limits}: semicolon-separated {@code laneKey=N} entries,
     * the lane key being everything before the last {@code =} (for example
     * {@code source:worms=1;lane:taxonomy=2}).
     *
     * @param raw the configured value, possibly {@code null} or blank
     * @return the limits keyed by lane key, in the order given
     * @throws AppException if an entry is malformed
     */
    static Map<String, Integer> parseLaneLimits(String raw) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (String entry : raw == null ? new String[0] : raw.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            int separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new AppException("Invalid lane limit '" + entry.trim() + "': expected laneKey=N");
            }
            limits.put(entry.substring(0, separator).trim(),
                    parseInt(entry.substring(separator + 1).trim(), "bdq.execution.lane.limits"));
        }
        return limits;
    }

    /**
     * Looks a setting up, treating a blank value as absent.
     *
     * @param settings the setting lookup
     * @param key the property name
     * @return the trimmed value, or {@code null} if absent or blank
     */
    private static String setting(Function<String, String> settings, String key) {
        String value = settings.apply(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Parses a whole-number setting.
     *
     * @param raw the raw value
     * @param propertyName the property name, used in the error message
     * @return the parsed number
     * @throws AppException if {@code raw} is not a whole number
     */
    private static int parseInt(String raw, String propertyName) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new AppException("Invalid value for " + propertyName + ": must be a whole number", e);
        }
    }

    /**
     * Parses a millisecond duration setting.
     *
     * @param raw the raw value, or {@code null} if absent
     * @param propertyName the property name, used in the error message
     * @return the duration, or {@code null} if {@code raw} is {@code null}
     * @throws AppException if {@code raw} is not a whole number
     */
    private static Duration parseMillis(String raw, String propertyName) {
        if (raw == null) {
            return null;
        }
        try {
            return Duration.ofMillis(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            throw new AppException("Invalid value for " + propertyName + ": must be a whole number of milliseconds", e);
        }
    }

    /**
     * Parses {@code bdq.dataset.join.policies}: comma-separated {@code table=POLICY} entries, the
     * policy being one of {@link DatasetViewCardinalityPolicy}'s names (case-insensitive).
     *
     * @param raw the configured value, possibly blank
     * @return policies keyed by table name, in the order given
     * @throws AppException if an entry is malformed or names an unknown policy
     */
    static Map<String, DatasetViewCardinalityPolicy> parseJoinPolicies(String raw) {
        Map<String, DatasetViewCardinalityPolicy> policies = new java.util.LinkedHashMap<>();
        for (String entry : raw == null ? new String[0] : raw.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            int separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new AppException("Invalid join policy '" + entry.trim() + "': expected table=POLICY");
            }
            String table = entry.substring(0, separator).trim();
            String policy = entry.substring(separator + 1).trim();
            try {
                policies.put(table, DatasetViewCardinalityPolicy.valueOf(policy.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new AppException("Invalid join policy '" + policy + "' for table " + table + ": expected one of "
                        + java.util.Arrays.toString(DatasetViewCardinalityPolicy.values()));
            }
        }
        return policies;
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
