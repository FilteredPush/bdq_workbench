/** BdqWorkbenchApplication.java
 *
 * Command line entry point that parses arguments, loads configuration, and wires the core
 * BDQ Workbench pipeline services to run a headless dataset validation/amendment pass.
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

import java.io.PrintStream;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionService;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;
import org.filteredpush.bdq_workbench.reporting.DetailedResponseStreamExporter;
import org.filteredpush.bdq_workbench.reporting.RdfResponseExporter;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.reporting.SummaryReportExporter;
import org.filteredpush.bdq_workbench.reporting.UnresolvedResponsesExporter;
import org.filteredpush.bdq_workbench.reporting.XlsxReportExporter;
import org.filteredpush.bdq_workbench.test_discovery.ClasspathAnnotationTestDiscoveryService;
import org.filteredpush.bdq_workbench.test_discovery.DefaultTestBindingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entrypoint that wires core services for the workbench pipeline.
 *
 * <p>With no arguments on a graphical desktop, delegates to {@link BdqWorkbenchGui#launch()}.
 * Otherwise parses command line arguments into an {@link AppConfig} override map (via
 * {@link ConfigLoader}), validates the resulting configuration, runs the pipeline through
 * {@link WorkbenchFacade#run(AppConfig)}, and prints a one-line completion summary.
 *
 * <p>This class is not instantiable; all behavior is exposed through {@link #main(String[])}
 * and package-visible helpers used directly by tests.
 */
public final class BdqWorkbenchApplication {
    private static final Logger LOG = LoggerFactory.getLogger(BdqWorkbenchApplication.class);
    private static final String HELP_FLAG = "--help";
    private static final String HELP_SHORT_FLAG = "-h";

    private BdqWorkbenchApplication() {
    }

    /**
     * CLI entry point. Runs the application against the given arguments, writing normal output
     * to {@link System#out} and errors to {@link System#err}, and terminates the JVM with a
     * non-zero exit code on failure.
     *
     * @param args command line arguments; see {@link #renderUsage(PrintStream)} for the
     *     supported flags
     */
    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Executes the application logic for the given arguments without terminating the JVM,
     * writing to the supplied streams instead of {@link System#out}/{@link System#err}.
     *
     * <p>With no arguments on a non-headless environment, launches the GUI. Otherwise renders
     * help if requested, parses and validates the configuration, executes the pipeline, and
     * prints a completion summary. Configuration and startup problems ({@link AppException})
     * are reported as user-facing errors; any other exception is logged and reported as a
     * generic failure.
     *
     * @param args command line arguments
     * @param out stream for normal output (usage text and the completion summary)
     * @param err stream for error output
     * @return {@code 0} on success, {@code 2} on an argument parsing error, or {@code 1} on a
     *     configuration or execution failure
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
                LOG.info("Starting BDQ Workbench GUI");
                BdqWorkbenchGui.launch();
                return 0;
            }
            if (wantsHelp(args)) {
                LOG.debug("Rendering CLI help");
                renderUsage(out);
                return 0;
            }
            ParseResult parseResult = parseArguments(args);
            if (parseResult.error() != null) {
                err.println(parseResult.error());
                renderUsage(err);
                return 2;
            }
            AppConfig config = new ConfigLoader().load(parseResult.overrides());
            validateStartupConfig(config);
            LOG.info("Starting BDQ Workbench CLI run with dataset {}", config.datasetPath());
            ExecutionSummary summary = execute(config);
            render(summary, out);
            return 0;
        } catch (AppException e) {
            LOG.error("BDQ Workbench startup failed: {}", e.getMessage());
            err.println("BDQ Workbench startup failed: " + e.getMessage());
            err.println("Run with --help for usage.");
            return 1;
        } catch (Exception e) {
            LOG.error("BDQ Workbench execution failed", e);
            return 1;
        }
    }

    /**
     * Validates configuration values that must hold before a run can start.
     *
     * @param config the configuration to validate
     * @throws AppException if {@link AppConfig#threadCount()} is less than 1 or
     *     {@link AppConfig#datasetPath()} does not exist
     */
    static void validateStartupConfig(AppConfig config) {
        if (config.threadCount() < 1) {
            throw new AppException("Invalid thread count: bdq.threads must be >= 1");
        }
        if (Files.notExists(config.datasetPath())) {
            throw new AppException("Dataset input not found: " + config.datasetPath());
        }
    }

    /**
     * Checks whether the given arguments request help output.
     *
     * @param args command line arguments
     * @return {@code true} if either {@code --help} or {@code -h} appears among {@code args}
     */
    private static boolean wantsHelp(String[] args) {
        for (String arg : args) {
            if (HELP_FLAG.equals(arg) || HELP_SHORT_FLAG.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses command line flags into {@link ConfigLoader} override keys.
     *
     * @param args command line arguments, expected as alternating {@code --flag value} pairs
     *     (help flags are skipped)
     * @return the parsed overrides, or a result carrying a descriptive error message if an
     *     argument is unrecognized or missing its value
     */
    private static ParseResult parseArguments(String[] args) {
        Map<String, String> overrides = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (HELP_FLAG.equals(arg) || HELP_SHORT_FLAG.equals(arg)) {
                continue;
            }
            String key = switch (arg) {
                case "--dataset" -> "bdq.dataset";
                case "--usecase-file" -> "bdq.usecase.file";
                case "--rdf-files" -> "bdq.rdf.files";
                case "--usecase-id" -> "bdq.usecase.id";
                case "--discovery-packages" -> "bdq.discovery.packages";
                case "--threads" -> "bdq.threads";
                case "--dedup" -> "bdq.execution.dedup";
                default -> null;
            };
            if (key == null) {
                return new ParseResult(Map.of(), "Unknown argument: " + arg);
            }
            if (i + 1 >= args.length) {
                return new ParseResult(Map.of(), "Missing value for argument: " + arg);
            }
            overrides.put(key, args[++i]);
        }
        return new ParseResult(Map.copyOf(overrides), null);
    }

    /**
     * Writes CLI usage instructions.
     *
     * @param out stream to write the usage text to
     */
    private static void renderUsage(PrintStream out) {
        out.println("Usage: java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar [options]");
        out.println();
        out.println("With no options on a desktop environment, the GUI launcher opens.");
        out.println();
        out.println("Options:");
        out.println("  -h, --help                     Show this help");
        out.println("  --dataset <path>               Input dataset (.zip DwC-A or datapackage.json)");
        out.println("  --usecase-file <path>          Use case XML file");
        out.println("  --rdf-files <paths>            Comma-separated RDF/OWL files");
        out.println("  --usecase-id <id>              Optional use case identifier");
        out.println("  --discovery-packages <pkgs>    Comma-separated implementation packages");
        out.println("  --threads <n>                  Worker thread count (>= 1)");
        out.println("  --dedup <true|false>           Run each test once per distinct combination of");
        out.println("                                 input values instead of once per record (default true)");
    }

    /**
     * Writes a one-line completion summary for a finished run.
     *
     * @param summary the execution summary produced by the run
     * @param out stream to write the summary line to
     */
    static void render(ExecutionSummary summary, PrintStream out) {
        out.println("BDQ Workbench completed: " + summary.responses().size() + " outcomes");
    }

    /**
     * Wires the concrete pipeline services (ingest, RDF-backed policy resolution, classpath
     * test discovery, test binding, parallel execution, and export to summary/detailed/XLS/RDF
     * reports) into a {@link WorkbenchFacade} and runs it for the given configuration.
     *
     * @param config the configuration for this run
     * @return the summary of the executed run
     */
    static ExecutionSummary execute(AppConfig config) {
        WorkbenchFacade facade = new WorkbenchFacade(
                new DefaultIngestService(),
                new RdfPolicyResolverService(config.useCaseXml(), config.rdfDefinitions()),
                new ClasspathAnnotationTestDiscoveryService(config.implementationPackages()),
                new DefaultTestBindingService(),
                new ParallelPhaseExecutionService(config.threadCount(), new ReflectionExecutionAdapter(), config.dedupEnabled()),
                new ReportingService(List.of(
                        new SummaryReportExporter(),
                        new DetailedResponseStreamExporter(),
                        new XlsxReportExporter(),
                        new UnresolvedResponsesExporter(),
                        new RdfResponseExporter(config.rdfDefinitions()))));
        return facade.run(config);
    }

    /**
     * Outcome of parsing command line arguments.
     *
     * @param overrides the parsed {@link ConfigLoader} override keys/values
     * @param error a descriptive error message if parsing failed, or {@code null} on success
     */
    private record ParseResult(Map<String, String> overrides, String error) {
    }
}
