package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
        assertThat(out.toString()).contains("--gui").contains("--dataset-table <name>");
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
}
