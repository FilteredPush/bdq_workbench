package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;
import org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionService;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.model.RecordFilterSpec;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;
import org.filteredpush.bdq_workbench.reporting.DetailedResponseStreamExporter;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.reporting.SummaryReportExporter;
import org.filteredpush.bdq_workbench.test_discovery.DefaultTestBindingService;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.filteredpush.bdq_workbench.test_discovery.TestDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                        null,
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        StubImpl.class.getName(),
                        "validate",
                        null,
                        List.of(new MethodParameter(0, "record", ParameterRole.LEGACY_RECORD, "record", Map.class.getName(), true)),
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
                new ReportingService(List.of(new SummaryReportExporter(), new DetailedResponseStreamExporter())));

        ExecutionSummary summary = facade.run(new AppConfig(
                base.resolve("bdquc.xml"),
                List.of(base.resolve("bdqtest.ttl")),
                base.resolve("dataset.zip"),
                "uc1",
                List.of("org.filteredpush"),
                1,
                true));

        assertThat(summary.responses()).isNotEmpty();
        assertThat(summary.responses()).anyMatch(r -> r.testId().equals("urn:test:validate"));
        assertThat(summary.responses()).anyMatch(r -> "RUN_HAS_RESULT".equals(r.responseStatus()));
    }

    @Test
    void filtersRecordsBeforeRunningPipeline(@TempDir Path tempDir) throws Exception {
        Path useCase = tempDir.resolve("bdquc.xml");
        Path tests = tempDir.resolve("bdqtest.ttl");
        Files.copy(Path.of("src", "test", "resources", "integration", "bdquc.xml"), useCase);
        Files.copy(Path.of("src", "test", "resources", "integration", "bdqtest.ttl"), tests);
        Path archive = createDatasetArchive(tempDir);
        var resolver = new RdfPolicyResolverService(useCase, List.of(tests));
        TestDiscoveryService discovery = () -> {
            try {
                Method m = StubImpl.class.getMethod("validate", Map.class);
                return List.of(new DiscoveredImplementation(
                        "urn:test:validate",
                        null,
                        TestType.VALIDATION,
                        Phase.PRE_AMENDMENT,
                        StubImpl.class.getName(),
                        "validate",
                        null,
                        List.of(new MethodParameter(0, "record", ParameterRole.LEGACY_RECORD, "record", Map.class.getName(), true)),
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
                new ReportingService(List.of(new SummaryReportExporter(), new DetailedResponseStreamExporter())));

        ExecutionSummary summary = facade.run(new AppConfig(
                useCase,
                List.of(tests),
                archive,
                "uc1",
                List.of("org.filteredpush"),
                1,
                true,
                RecordFilterSpec.parse("country=Canada")));

        assertThat(summary.responses()).hasSize(2);
        assertThat(summary.metadata().inputSingleRecordCount()).isEqualTo(2);
        assertThat(summary.metadata().filteredSingleRecordCount()).isEqualTo(1);
        assertThat(summary.metadata().recordFilters()).containsEntry("country", List.of("Canada"));
        assertThat(summary.responses()).allMatch(response -> response.recordId().equals("occ-1"));
    }

    private static Path createDatasetArchive(Path tempDir) throws Exception {
        Path archive = tempDir.resolve("dataset.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("occurrence.txt"));
            String content = "occurrenceID\tcountry\tscientificName\n"
                    + "occ-1\tCanada\tQuercus alba\n"
                    + "occ-2\tMexico\tPinus oocarpa\n";
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }

    public static class StubImpl {
        public StubDQResponse validate(Map<String, String> record) {
            return new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", record.get("occurrenceID"));
        }
    }

    public static class StubDQResponse {
        private final StubResultState resultState;
        private final StubResultValue value;
        private final String comment;

        StubDQResponse(String status, Object value, String comment) {
            this.resultState = new StubResultState(status);
            this.value = new StubResultValue(value);
            this.comment = comment;
        }

        public StubResultState getResultState() {
            return resultState;
        }

        public StubResultValue getValue() {
            return value;
        }

        public String getComment() {
            return comment;
        }
    }

    public static class StubResultState {
        private final String label;

        StubResultState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static class StubResultValue {
        private final Object object;

        StubResultValue(Object object) {
            this.object = object;
        }

        public Object getObject() {
            return object;
        }
    }
}
