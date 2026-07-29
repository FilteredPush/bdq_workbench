package org.filteredpush.bdq_workbench.app;

import java.io.PrintStream;
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

    private BdqWorkbenchApplication() {
    }

    public static void main(String[] args) {
        try {
            AppConfig config = new ConfigLoader().load();
            WorkbenchFacade facade = new WorkbenchFacade(
                    new DefaultIngestService(),
                    new RdfPolicyResolverService(config.useCaseXml(), config.rdfDefinitions()),
                    new ClasspathAnnotationTestDiscoveryService(config.implementationPackages()),
                    new DefaultTestBindingService(),
                    new ParallelPhaseExecutionService(config.threadCount(), new ReflectionExecutionAdapter()),
                    new ReportingService(List.of(new SummaryReportExporter(), new XlsCompatibilityExporter())));
            ExecutionSummary summary = facade.run(config);
            render(summary, System.out);
        } catch (Exception e) {
            LOG.error("BDQ Workbench execution failed", e);
            System.exit(1);
        }
    }

    static void render(ExecutionSummary summary, PrintStream out) {
        out.println("BDQ Workbench completed: " + summary.responses().size() + " outcomes");
    }
}
