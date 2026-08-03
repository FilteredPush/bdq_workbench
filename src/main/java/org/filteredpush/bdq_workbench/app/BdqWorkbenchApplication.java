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
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.reporting.SummaryReportExporter;
import org.filteredpush.bdq_workbench.reporting.XlsCompatibilityExporter;
import org.filteredpush.bdq_workbench.test_discovery.ClasspathAnnotationTestDiscoveryService;
import org.filteredpush.bdq_workbench.test_discovery.DefaultTestBindingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main entrypoint that wires core services for the workbench pipeline. */
public final class BdqWorkbenchApplication {
    private static final Logger LOG = LoggerFactory.getLogger(BdqWorkbenchApplication.class);
    private static final String HELP_FLAG = "--help";
    private static final String HELP_SHORT_FLAG = "-h";

    private BdqWorkbenchApplication() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

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

    static void validateStartupConfig(AppConfig config) {
        if (config.threadCount() < 1) {
            throw new AppException("Invalid thread count: bdq.threads must be >= 1");
        }
        if (Files.notExists(config.datasetPath())) {
            throw new AppException("Dataset input not found: " + config.datasetPath());
        }
    }

    private static boolean wantsHelp(String[] args) {
        for (String arg : args) {
            if (HELP_FLAG.equals(arg) || HELP_SHORT_FLAG.equals(arg)) {
                return true;
            }
        }
        return false;
    }

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
    }

    static void render(ExecutionSummary summary, PrintStream out) {
        out.println("BDQ Workbench completed: " + summary.responses().size() + " outcomes");
    }

    static ExecutionSummary execute(AppConfig config) {
        WorkbenchFacade facade = new WorkbenchFacade(
                new DefaultIngestService(),
                new RdfPolicyResolverService(config.useCaseXml(), config.rdfDefinitions()),
                new ClasspathAnnotationTestDiscoveryService(config.implementationPackages()),
                new DefaultTestBindingService(),
                new ParallelPhaseExecutionService(config.threadCount(), new ReflectionExecutionAdapter()),
                new ReportingService(List.of(
                        new SummaryReportExporter(),
                        new DetailedResponseStreamExporter(),
                        new XlsCompatibilityExporter())));
        return facade.run(config);
    }

    private record ParseResult(Map<String, String> overrides, String error) {
    }
}
