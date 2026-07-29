package org.filteredpush.bdq_workbench.app;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;

/** Minimal desktop launcher for collecting startup parameters and monitoring execution progress. */
final class BdqWorkbenchGui {

    private BdqWorkbenchGui() {
    }

    static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                ConfigLoader loader = new ConfigLoader();
                AppConfig defaults = loader.load(Map.of());
                createFrame(loader, defaults).setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(
                        null,
                        "Unable to start BDQ Workbench GUI: " + e.getMessage(),
                        "Startup failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static JFrame createFrame(ConfigLoader loader, AppConfig defaults) {
        JFrame frame = new JFrame("BDQ Workbench");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 500);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        frame.setContentPane(root);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JTextField dataset = addField(form, "Dataset", defaults.datasetPath().toString());
        JTextField useCaseFile = addField(form, "Use case XML", defaults.useCaseXml().toString());
        JTextField rdfFiles = addField(form, "RDF files (comma-separated)", joinPaths(defaults.rdfDefinitions()));
        JTextField useCaseId = addField(form, "Use case ID", defaults.useCaseId());
        JTextField discoveryPackages = addField(
                form,
                "Discovery packages (comma-separated)",
                String.join(",", defaults.implementationPackages()));
        JTextField threads = addField(form, "Threads", Integer.toString(defaults.threadCount()));

        root.add(form, BorderLayout.NORTH);

        JTextArea status = new JTextArea();
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        root.add(new JScrollPane(status), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(10, 10));
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setVisible(false);
        bottom.add(progress, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton run = new JButton("Run");
        controls.add(run);
        bottom.add(controls, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        run.addActionListener(e -> {
            run.setEnabled(false);
            progress.setVisible(true);
            status.append("Starting BDQ Workbench...\n");
            Map<String, String> overrides = new HashMap<>();
            overrides.put("bdq.dataset", dataset.getText().trim());
            overrides.put("bdq.usecase.file", useCaseFile.getText().trim());
            overrides.put("bdq.rdf.files", rdfFiles.getText().trim());
            overrides.put("bdq.usecase.id", useCaseId.getText().trim());
            overrides.put("bdq.discovery.packages", discoveryPackages.getText().trim());
            overrides.put("bdq.threads", threads.getText().trim());

            SwingWorker<ExecutionSummary, Void> worker = new SwingWorker<>() {
                @Override
                protected ExecutionSummary doInBackground() {
                    AppConfig config = loader.load(Map.copyOf(overrides));
                    BdqWorkbenchApplication.validateStartupConfig(config);
                    return BdqWorkbenchApplication.execute(config);
                }

                @Override
                protected void done() {
                    try {
                        ExecutionSummary summary = get();
                        status.append("Completed: " + summary.responses().size() + " outcomes\n");
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        status.append("Failed: " + cause.getMessage() + "\n");
                        JOptionPane.showMessageDialog(
                                frame,
                                "BDQ Workbench failed: " + cause.getMessage(),
                                "Execution failed",
                                JOptionPane.ERROR_MESSAGE);
                    } finally {
                        progress.setVisible(false);
                        run.setEnabled(true);
                    }
                }
            };
            worker.execute();
        });

        return frame;
    }

    private static JTextField addField(JPanel panel, String label, String defaultValue) {
        JPanel row = new JPanel(new BorderLayout(8, 8));
        row.add(new JLabel(label), BorderLayout.WEST);
        JTextField field = new JTextField(defaultValue == null ? "" : defaultValue);
        row.add(field, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        panel.add(row);
        return field;
    }

    private static String joinPaths(java.util.List<Path> paths) {
        return paths.stream().map(Path::toString).reduce((left, right) -> left + "," + right).orElse("");
    }
}
