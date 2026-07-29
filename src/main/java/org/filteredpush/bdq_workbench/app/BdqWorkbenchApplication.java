package org.filteredpush.bdq_workbench.app;

import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionService;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;
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
            if (wantsHelp(args)) {
                renderUsage(out);
                return 0;
            }
            String unknownArgument = firstUnknownArgument(args);
            if (unknownArgument != null) {
                err.println("Unknown argument: " + unknownArgument);
                renderUsage(err);
                return 2;
            }
            AppConfig config = new ConfigLoader().load();
            validateStartup(config);
            WorkbenchFacade facade = new WorkbenchFacade(
                    new DefaultIngestService(),
                    new RdfPolicyResolverService(config.useCaseXml(), config.rdfDefinitions()),
                    new ClasspathAnnotationTestDiscoveryService(config.implementationPackages()),
                    new DefaultTestBindingService(),
                    new ParallelPhaseExecutionService(config.threadCount(), new ReflectionExecutionAdapter()),
                    new ReportingService(List.of(new SummaryReportExporter(), new XlsCompatibilityExporter())));
            ExecutionSummary summary = facade.run(config);
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

    private static void validateStartup(AppConfig config) {
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

    private static String firstUnknownArgument(String[] args) {
        for (String arg : args) {
            if (!HELP_FLAG.equals(arg) && !HELP_SHORT_FLAG.equals(arg)) {
                return arg;
            }
        }
        return null;
    }

    private static void renderUsage(PrintStream out) {
        out.println("Usage: java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar [--help]");
        out.println();
        out.println("Configuration is provided through system properties:");
        out.println("  -Dbdq.dataset=<path>           Input dataset (.zip DwC-A or datapackage.json)");
        out.println("  -Dbdq.usecase.file=<path>      Use case XML file");
        out.println("  -Dbdq.rdf.files=<paths>        Comma-separated RDF/OWL files");
        out.println("  -Dbdq.usecase.id=<id>          Optional use case identifier");
        out.println("  -Dbdq.discovery.packages=<pkgs> Comma-separated implementation packages");
        out.println("  -Dbdq.threads=<n>              Worker thread count (>= 1)");
    }

    static void render(ExecutionSummary summary, PrintStream out) {
        out.println("BDQ Workbench completed: " + summary.responses().size() + " outcomes");
    }
}
