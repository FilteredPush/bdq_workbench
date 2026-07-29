package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionService;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.reporting.SummaryReportExporter;
import org.filteredpush.bdq_workbench.test_discovery.DefaultTestBindingService;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.filteredpush.bdq_workbench.test_discovery.TestDiscoveryService;
import org.junit.jupiter.api.Test;

class WorkbenchFacadeIT {

    @Test
    void runsPipelineFromInputToSummaryOutput() throws Exception {
        Path base = Path.of("src", "test", "resources", "integration");
        var resolver = new RdfPolicyResolverService(base.resolve("bdquc.xml"), List.of(base.resolve("bdqtest.ttl")));
        TestDiscoveryService discovery = () -> {
            try {
                Method m = StubImpl.class.getMethod("validate", Map.class);
                return List.of(new DiscoveredImplementation(
                        "urn:test:validate",
                        Phase.PRE_AMENDMENT,
                        StubImpl.class.getName(),
                        "validate",
                        Map.of(),
                        new StubImpl(),
                        m));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        };
        WorkbenchFacade facade = new WorkbenchFacade(
                new DefaultIngestService(),
                resolver,
                discovery,
                new DefaultTestBindingService(),
                new ParallelPhaseExecutionService(1, new ReflectionExecutionAdapter()),
                new ReportingService(List.of(new SummaryReportExporter())));

        ExecutionSummary summary = facade.run(new AppConfig(
                base.resolve("bdquc.xml"),
                List.of(base.resolve("bdqtest.ttl")),
                base.resolve("dataset.zip"),
                "uc1",
                List.of("org.filteredpush"),
                1));

        assertThat(summary.responses()).isNotEmpty();
        assertThat(summary.responses()).anyMatch(r -> r.testId().equals("urn:test:validate"));
    }

    public static class StubImpl {
        public boolean validate(Map<String, String> record) {
            return record.containsKey("occurrenceID");
        }
    }
}
