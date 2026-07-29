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
        assertThat(out.toString()).contains("Usage: java -jar");
        assertThat(err.toString()).isEmpty();
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
        String originalDataset = System.getProperty("bdq.dataset");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setProperty("bdq.dataset", "missing-dataset.zip");

            int exitCode = BdqWorkbenchApplication.run(new String[0], printStream(out), printStream(err));

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("BDQ Workbench startup failed: Dataset input not found: missing-dataset.zip");
            assertThat(err.toString()).contains("Run with --help for usage.");
            assertThat(out.toString()).isEmpty();
        } finally {
            if (originalDataset == null) {
                System.clearProperty("bdq.dataset");
            } else {
                System.setProperty("bdq.dataset", originalDataset);
            }
        }
    }

    private static PrintStream printStream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer);
    }
}
