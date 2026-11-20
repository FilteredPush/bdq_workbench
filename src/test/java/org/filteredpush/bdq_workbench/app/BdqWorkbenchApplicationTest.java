package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.junit.jupiter.api.Test;

class BdqWorkbenchApplicationTest {

    @Test
    void printsHelpAndExitsWithoutRunningPipeline() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BdqWorkbenchApplication.run(new String[] {"--help"}, printStream(out), printStream(err));

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Usage: java -jar").contains("--dataset <path>").contains("--record-filter <field=values>");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void rejectsInvalidRecordFilterSyntaxWithFriendlyStartupError() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BdqWorkbenchApplication.run(
                new String[] {"--dataset", "dataset.zip", "--record-filter", "country="},
                printStream(out),
                printStream(err));

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("Invalid record filter");
    }

    @Test
    void rejectsUnknownArgumentsWithUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BdqWorkbenchApplication.run(new String[] {"--bad-flag"}, printStream(out), printStream(err));

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Unknown argument: --bad-flag").contains("Usage: java -jar");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void returnsFriendlyStartupErrorWhenDatasetIsMissing() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BdqWorkbenchApplication.run(
                new String[] {"--dataset", "missing-dataset.zip"},
                printStream(out),
                printStream(err));

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("BDQ Workbench startup failed: Dataset input not found: missing-dataset.zip");
        assertThat(err.toString()).contains("Run with --help for usage.");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void acceptsRepeatableJoinPoliciesAndRejectsUnknownPolicies() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int accepted = BdqWorkbenchApplication.run(new String[] {
                "--dataset", "missing-dataset.zip",
                "--join-policy", "identification=EXPAND",
                "--join-policy", "multimedia.txt=first_row"}, printStream(out), printStream(err));

        assertThat(err.toString()).doesNotContain("Unknown argument")
                .contains("Dataset input not found: missing-dataset.zip");

        ByteArrayOutputStream badErr = new ByteArrayOutputStream();
        int rejected = BdqWorkbenchApplication.run(new String[] {
                "--dataset", "missing-dataset.zip", "--join-policy", "identification=SOMETIMES"},
                printStream(new ByteArrayOutputStream()), printStream(badErr));

        assertThat(rejected).isEqualTo(accepted);
        assertThat(badErr.toString()).contains("Invalid join policy 'SOMETIMES' for table identification");
    }

    @Test
    void acceptsExecutionSchedulingOptionsAndRejectsMalformedOverrides() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        BdqWorkbenchApplication.run(new String[] {
                "--dataset", "missing-dataset.zip",
                "--external-concurrency", "1",
                "--retries", "3",
                "--reuse-pre-results", "false",
                "--execution-override", "org.example.Remote=external,lane:worms",
                "--execution-override", "org.example.Local#validate=local",
                "--lane-limit", "lane:worms=1",
                "--lane-limit", "source:irmng=2"}, printStream(new ByteArrayOutputStream()), printStream(err));

        assertThat(err.toString()).doesNotContain("Unknown argument")
                .doesNotContain("Invalid")
                .contains("Dataset input not found: missing-dataset.zip");

        ByteArrayOutputStream badErr = new ByteArrayOutputStream();
        BdqWorkbenchApplication.run(new String[] {
                "--dataset", "missing-dataset.zip", "--execution-override", "org.example.Remote=sometimes"},
                printStream(new ByteArrayOutputStream()), printStream(badErr));

        assertThat(badErr.toString()).contains("Invalid execution override 'sometimes' for org.example.Remote");
    }

    @Test
    void helpDescribesExecutionSchedulingOptions() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        BdqWorkbenchApplication.run(new String[] {"--help"}, printStream(out), printStream(new ByteArrayOutputStream()));

        assertThat(out.toString()).contains("--external-concurrency <n>", "--retries <n>", "--reuse-pre-results",
                "--execution-override <target=spec>", "--lane-limit <laneKey=n>");
    }

    @Test
    void helpDescribesJoinPolicies() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        BdqWorkbenchApplication.run(new String[] {"--help"}, printStream(out), printStream(new ByteArrayOutputStream()));

        assertThat(out.toString()).contains("--join-policy <table=POLICY>").contains("EXPAND (test each");
    }

    @Test
    void rejectsOptionWithoutValueWithUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BdqWorkbenchApplication.run(new String[] {"--dataset"}, printStream(out), printStream(err));

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Missing value for argument: --dataset").contains("Usage: java -jar");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void noArgsInHeadlessModeFallsBackToValidatedCliStartup() {
        String originalHeadless = System.getProperty("java.awt.headless");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setProperty("java.awt.headless", "true");

            int exitCode = BdqWorkbenchApplication.run(new String[0], printStream(out), printStream(err));

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("BDQ Workbench startup failed: Dataset input not found: dataset.zip");
        } finally {
            if (originalHeadless == null) {
                System.clearProperty("java.awt.headless");
            } else {
                System.setProperty("java.awt.headless", originalHeadless);
            }
        }
    }

    @Test
    void guiFlagIsAcceptedAlongsideOtherOptions() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = withHeadless(() ->
                BdqWorkbenchApplication.run(
                        new String[] {"--gui", "--dataset", "missing.zip"}, printStream(out), printStream(err)));

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString())
                .contains("Cannot start the GUI: no graphical display is available.")
                .doesNotContain("Unknown argument");
    }

    @Test
    void withoutGuiFlagOptionsRunHeadlessly() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = withHeadless(() ->
                BdqWorkbenchApplication.run(
                        new String[] {"--dataset", "missing.zip"}, printStream(out), printStream(err)));

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("Dataset input not found: missing.zip");
    }

    @Test
    void helpDocumentsTheGuiFlag() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BdqWorkbenchApplication.run(new String[] {"--help"}, printStream(out), printStream(err));

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("--gui").contains("--dataset-table <name>").contains("--dataset-view <path>");
    }

    /**
     * Runs a supplier with the JVM forced into headless mode, restoring the previous setting.
     *
     * @param action the action to run headless
     * @return the action's result
     */
    private static int withHeadless(java.util.function.IntSupplier action) {
        String originalHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("java.awt.headless", "true");
            return action.getAsInt();
        } finally {
            if (originalHeadless == null) {
                System.clearProperty("java.awt.headless");
            } else {
                System.setProperty("java.awt.headless", originalHeadless);
            }
        }
    }

    private static PrintStream printStream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer);
    }

    @Test
    void completionOutputWarnsAboutSyntheticInput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        org.filteredpush.bdq_workbench.model.ExecutionSummary summary = new org.filteredpush.bdq_workbench.model.ExecutionSummary(
                java.util.List.of(), null, new org.filteredpush.bdq_workbench.model.RecordDataset(java.util.List.of(),
                        java.util.List.of(), org.filteredpush.bdq_workbench.model.DatasetInputDescription.none()
                                .withSyntheticMarkers(new org.filteredpush.bdq_workbench.model.SyntheticDataMarkers(
                                        2, 2, 0, 0, java.util.List.of("s1", "s2")))));

        BdqWorkbenchApplication.render(summary, printStream(out));

        assertThat(out.toString()).startsWith("WARNING: 2 of 2 input record(s) are marked as synthetic or modified "
                + "example data (2 wholly synthetic).").contains("BDQ Workbench completed: 0 outcomes");
    }

    private static Map<String, UseCase> publishedStyleUseCases() {
        Map<String, UseCase> useCases = new LinkedHashMap<>();
        useCases.put("https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22",
                new UseCase("https://rs.tdwg.org/bdquc/terms/version/Spatial-Temporal_Patterns-2026-04-22",
                        "Spatial-Temporal Patterns", "urn:uuid:policy-stp"));
        useCases.put("https://rs.tdwg.org/bdquc/terms/version/Alien-Species-2026-04-22",
                new UseCase("https://rs.tdwg.org/bdquc/terms/version/Alien-Species-2026-04-22",
                        "Alien Species", "urn:uuid:policy-as"));
        // the same use case under its term IRI, as the published bdquc.xml also yields
        useCases.put("https://rs.tdwg.org/bdquc/terms/Alien-Species",
                new UseCase("https://rs.tdwg.org/bdquc/terms/Alien-Species", "Alien Species", "urn:uuid:policy-as"));
        return useCases;
    }

    @Test
    void commandLineRunWithoutUseCaseListsTheKnownUseCases() {
        assertThatThrownBy(() -> BdqWorkbenchApplication.checkUseCase(publishedStyleUseCases(), "", "bdquc.xml"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("No use case given; name one with --usecase-id.")
                .hasMessageContaining("Known use cases in bdquc.xml:")
                .hasMessageContaining("https://rs.tdwg.org/bdquc/terms/Alien-Species  (Alien Species)")
                .hasMessageContaining("https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns  (Spatial-Temporal Patterns)")
                .satisfies(e -> assertThat(e.getMessage().split("terms/Alien-Species  ", -1)).hasSize(2));
    }

    @Test
    void commandLineRunWithUnknownUseCaseListsTheKnownUseCases() {
        assertThatThrownBy(() -> BdqWorkbenchApplication.checkUseCase(
                        publishedStyleUseCases(), "https://rs.tdwg.org/bdquc/terms/No-Such-Use-Case", "bdquc.xml"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Unknown use case: https://rs.tdwg.org/bdquc/terms/No-Such-Use-Case.")
                .hasMessageContaining("https://rs.tdwg.org/bdquc/terms/Alien-Species  (Alien Species)");
    }

    @Test
    void commandLineRunAcceptsTermOrVersionIriOfAKnownUseCase() {
        assertThatCode(() -> BdqWorkbenchApplication.checkUseCase(publishedStyleUseCases(),
                "https://rs.tdwg.org/bdquc/terms/Spatial-Temporal_Patterns", "bdquc.xml")).doesNotThrowAnyException();
        assertThatCode(() -> BdqWorkbenchApplication.checkUseCase(publishedStyleUseCases(),
                "http://rs.tdwg.org/bdquc/terms/version/Alien-Species-2026-04-22", "bdquc.xml")).doesNotThrowAnyException();
    }

    @Test
    void usageSaysTheUseCaseIsRequiredOnTheCommandLine() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BdqWorkbenchApplication.run(new String[] {"--help"}, printStream(out), printStream(new ByteArrayOutputStream()));
        assertThat(out.toString()).contains("--usecase-id <id>              Use case to run (required without --gui)");
    }
}
