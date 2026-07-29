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
        assertThat(out.toString()).contains("Usage: java -jar").contains("--dataset <path>");
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

    private static PrintStream printStream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer);
    }
}
