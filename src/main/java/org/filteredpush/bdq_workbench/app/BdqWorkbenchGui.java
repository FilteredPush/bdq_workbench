package org.filteredpush.bdq_workbench.app;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
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
import org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionService;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;
import org.filteredpush.bdq_workbench.rdf_policy.UseCaseXmlParser;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.reporting.SummaryReportExporter;
import org.filteredpush.bdq_workbench.reporting.XlsCompatibilityExporter;
import org.filteredpush.bdq_workbench.test_discovery.ClasspathAnnotationTestDiscoveryService;
import org.filteredpush.bdq_workbench.test_discovery.DefaultTestBindingService;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Desktop launcher for collecting startup parameters and monitoring execution progress. */
final class BdqWorkbenchGui {
    private static final Logger LOG = LoggerFactory.getLogger(BdqWorkbenchGui.class);

    private static final String DEFAULT_USECASE_SOURCE = "https://bdq.tdwg.org/draft/dist/bdquc.xml";
    private static final String DEFAULT_TEST_DEFINITIONS_SOURCE = "https://bdq.tdwg.org/draft/dist/bdqtest.ttl";
    private static final String DEFAULT_ONTOLOGY_SOURCE = "https://bdq.tdwg.org/draft/vocabulary/bdqffdq.ttl";

    private BdqWorkbenchGui() {
    }

    static void launch() {
        LOG.debug("Scheduling BDQ Workbench GUI startup");
        SwingUtilities.invokeLater(() -> {
            try {
                ConfigLoader loader = new ConfigLoader();
                AppConfig defaults = loader.load(Map.of());
                createFrame(defaults).setVisible(true);
                LOG.info("BDQ Workbench GUI started");
            } catch (Exception e) {
                LOG.error("Unable to start BDQ Workbench GUI", e);
                JOptionPane.showMessageDialog(
                        null,
                        "Unable to start BDQ Workbench GUI: " + e.getMessage(),
                        "Startup failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static JFrame createFrame(AppConfig defaults) {
        JFrame frame = new JFrame("BDQ Workbench");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(980, 640);

        CachedResourceResolver resolver = new CachedResourceResolver();

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        frame.setContentPane(root);

        CardLayout cards = new CardLayout();
        JPanel cardPanel = new JPanel(cards);

        JTextArea statusArea = new JTextArea();
        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);

        JPanel monitorPanel = new JPanel(new BorderLayout(8, 8));
        monitorPanel.add(new JScrollPane(statusArea), BorderLayout.CENTER);
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setVisible(false);
        monitorPanel.add(progress, BorderLayout.NORTH);

        JPanel monitorControls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton backToSetup = new JButton("Back to Setup");
        JButton startRun = new JButton("Start Run");
        startRun.setEnabled(false);
        JButton closeButton = new JButton("Quit");
        monitorControls.add(backToSetup);
        monitorControls.add(startRun);
        monitorControls.add(closeButton);
        monitorPanel.add(monitorControls, BorderLayout.SOUTH);

        JPanel setupPanel = new JPanel(new BorderLayout(10, 10));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        PickerField dataset = addPickerField(form, frame, "Dataset", defaults.datasetPath().toString());

        JComboBox<UseCaseChoice> useCaseChoice = new JComboBox<>();
        addComboRow(form, "Use case", useCaseChoice);
        JTextArea useCaseLoadStatus = new JTextArea(3, 40);
        useCaseLoadStatus.setEditable(false);
        useCaseLoadStatus.setLineWrap(true);
        useCaseLoadStatus.setWrapStyleWord(true);
        useCaseLoadStatus.setBorder(BorderFactory.createEtchedBorder());
        form.add(useCaseLoadStatus);

        JButton toggleAdvanced = new JButton("Show Advanced Options");
        JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toggleRow.add(toggleAdvanced);
        form.add(toggleRow);

        JPanel advanced = new JPanel();
        advanced.setLayout(new BoxLayout(advanced, BoxLayout.Y_AXIS));
        advanced.setVisible(false);

        JTextField useCaseSource = addField(advanced, "Use case file/URL", DEFAULT_USECASE_SOURCE);
        JButton loadUseCases = new JButton("Load use cases");
        JButton pickUseCaseFile = new JButton("Pick use case file");
        JPanel useCaseButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        useCaseButtons.add(loadUseCases);
        useCaseButtons.add(pickUseCaseFile);
        advanced.add(useCaseButtons);

        JTextField testDefinitionsSource = addField(advanced, "Test definitions file/URL", DEFAULT_TEST_DEFINITIONS_SOURCE);
        JButton pickTestDefinitionsFile = new JButton("Pick test definitions file");
        JButton loadTests = new JButton("Load tests");
        JPanel testDefinitionsButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        testDefinitionsButtons.add(pickTestDefinitionsFile);
        testDefinitionsButtons.add(loadTests);
        advanced.add(testDefinitionsButtons);
        JTextArea testDefinitionLoadStatus = new JTextArea(4, 40);
        testDefinitionLoadStatus.setEditable(false);
        testDefinitionLoadStatus.setLineWrap(true);
        testDefinitionLoadStatus.setWrapStyleWord(true);
        testDefinitionLoadStatus.setBorder(BorderFactory.createEtchedBorder());
        advanced.add(testDefinitionLoadStatus);

        JTextField additionalTestDefinitions = addField(advanced, "Additional test definition files/URLs (comma-separated)", "");
        JTextField ontologySource = addField(advanced, "BDQ FFDQ ontology file/URL", DEFAULT_ONTOLOGY_SOURCE);
        JTextField discoveryPackages = addField(
                advanced,
                "Discovery packages (comma-separated)",
                String.join(",", defaults.implementationPackages()));
        JTextField threads = addField(
                advanced,
                "Threads",
                Integer.toString(defaultThreadCount()));

        JCheckBox runWithAvailableOnly = new JCheckBox("Continue when some tests are unresolved", true);
        advanced.add(runWithAvailableOnly);
        setupPanel.add(form, BorderLayout.NORTH);

        JTextArea setupInfo = new JTextArea();
        setupInfo.setEditable(false);
        setupInfo.setLineWrap(true);
        setupInfo.setWrapStyleWord(true);
        setupInfo.setText("Default test definitions are retrieved and cached from:\n"
                + "  " + DEFAULT_TEST_DEFINITIONS_SOURCE + "\n"
                + "Use advanced options to change the test definitions source, add more test definition files, or change ontology source.");
        setupPanel.add(new JScrollPane(setupInfo), BorderLayout.CENTER);

        JPanel setupControls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton run = new JButton("Run");
        JButton exit = new JButton("Quit");
        setupControls.add(run);
        setupControls.add(exit);
        setupPanel.add(setupControls, BorderLayout.SOUTH);

        cardPanel.add(setupPanel, "setup");
        cardPanel.add(monitorPanel, "monitor");
        root.add(cardPanel, BorderLayout.CENTER);

        final PreflightState[] state = new PreflightState[1];

        toggleAdvanced.addActionListener(e -> {
            advanced.setVisible(!advanced.isVisible());
            toggleAdvanced.setText(advanced.isVisible() ? "Hide Advanced Options" : "Show Advanced Options");
            setupPanel.revalidate();
            setupPanel.repaint();
        });
        form.add(advanced);

        loadUseCases.addActionListener(e -> loadUseCasesIntoCombo(
                useCaseSource.getText().trim(),
                resolver,
                useCaseChoice,
                useCaseLoadStatus,
                defaults.useCaseId()));

        pickUseCaseFile.addActionListener(e -> {
            String selected = chooseFile(frame, "Select use case RDF");
            if (selected != null) {
                useCaseSource.setText(selected);
                loadUseCasesIntoCombo(selected, resolver, useCaseChoice, useCaseLoadStatus, defaults.useCaseId());
            }
        });
        pickTestDefinitionsFile.addActionListener(e -> {
            String selected = chooseFile(frame, "Select test definitions RDF");
            if (selected != null) {
                testDefinitionsSource.setText(selected);
            }
        });
        loadTests.addActionListener(e -> loadTestDefinitions(
                testDefinitionsSource.getText().trim(),
                additionalTestDefinitions.getText().trim(),
                resolver,
                testDefinitionLoadStatus));

        exit.addActionListener(e -> exitApplication(frame));
        closeButton.addActionListener(e -> exitApplication(frame));

        backToSetup.addActionListener(e -> {
            if (!progress.isVisible()) {
                cards.show(cardPanel, "setup");
            }
        });

        run.addActionListener(e -> {
            run.setEnabled(false);
            cards.show(cardPanel, "monitor");
            setStatus(statusArea, "Preparing run configuration...\n");
            progress.setVisible(true);
            startRun.setEnabled(false);

            SwingWorker<PreflightState, Void> preflight = new SwingWorker<>() {
                @Override
                protected PreflightState doInBackground() {
                    LOG.debug("Preparing preflight mapping");
                    AppConfig config = buildConfig(
                            dataset.field().getText().trim(),
                            selectedUseCaseId(useCaseChoice),
                            useCaseSource.getText().trim(),
                            testDefinitionsSource.getText().trim(),
                            additionalTestDefinitions.getText().trim(),
                            ontologySource.getText().trim(),
                            discoveryPackages.getText().trim(),
                            threads.getText().trim(),
                            resolver,
                            defaults);
                    BdqWorkbenchApplication.validateStartupConfig(config);

                    RdfPolicyResolverService policyResolver = new RdfPolicyResolverService(config.useCaseXml(), config.rdfDefinitions());
                    ExecutionPlan plan = policyResolver.resolve(config.useCaseId());
                    var discovery = new ClasspathAnnotationTestDiscoveryService(config.implementationPackages());
                    var discovered = discovery.discover();
                    TestBindingResult binding = new DefaultTestBindingService().bind(plan.tests(), discovered, Map.of());

                    return new PreflightState(config, plan, discovered.size(), binding);
                }

                @Override
                protected void done() {
                    try {
                        state[0] = get();
                        LOG.debug("Preflight mapping complete: {} runnable, {} unresolved",
                                state[0].binding().bindings().size(),
                                state[0].binding().unresolved().size());
                        String preflightMessage = renderPreflightMessage(state[0]);
                        setStatus(statusArea, preflightMessage);
                        boolean complete = state[0].isFullyResolved();
                        if (!complete && !runWithAvailableOnly.isSelected()) {
                            appendStatus(statusArea, "\nRun is blocked until unresolved tests are handled.\n");
                            startRun.setEnabled(false);
                        } else {
                            startRun.setText(complete ? "Start Run" : "Start Available Tests");
                            startRun.setEnabled(true);
                        }
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        LOG.error("Preflight mapping failed", cause);
                        setStatus(statusArea, "Failed to prepare run: " + cause.getMessage() + "\n");
                        startRun.setEnabled(false);
                    } finally {
                        progress.setVisible(false);
                        run.setEnabled(true);
                    }
                }
            };
            preflight.execute();
        });

        startRun.addActionListener(e -> {
            if (state[0] == null) {
                return;
            }
            startRun.setEnabled(false);
            backToSetup.setEnabled(false);
            progress.setVisible(true);
            appendStatus(statusArea, "\nStarting execution...\n");

            SwingWorker<ExecutionSummary, Void> worker = new SwingWorker<>() {
                @Override
                protected ExecutionSummary doInBackground() {
                    LOG.info("Starting BDQ Workbench execution");
                    return runWorkbench(state[0].config());
                }

                @Override
                protected void done() {
                    try {
                        ExecutionSummary summary = get();
                        LOG.info("BDQ Workbench execution complete: {} outcomes", summary.responses().size());
                        appendStatus(statusArea, "Completed: " + summary.responses().size() + " outcomes\n");
                        Iterator <Response> i = summary.responses().iterator();
                        while (i.hasNext()) {
							Response r = i.next();
							appendStatus(statusArea, String.format(
									" - %s: %s -> %s (%s)\n",
									r.testId(),
									r.recordId(),
									r.status(),
									r.message()));
						}
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        LOG.error("BDQ Workbench execution failed", cause);
                        appendStatus(statusArea, "Failed: " + cause.getMessage() + "\n");
                        JOptionPane.showMessageDialog(
                                frame,
                                "BDQ Workbench failed: " + cause.getMessage(),
                                "Execution failed",
                                JOptionPane.ERROR_MESSAGE);
                    } finally {
                        progress.setVisible(false);
                        backToSetup.setEnabled(true);
                    }
                }
            };
            worker.execute();
        });

        loadUseCasesIntoCombo(
                useCaseSource.getText().trim(),
                resolver,
                useCaseChoice,
                useCaseLoadStatus,
                defaults.useCaseId());
        loadTestDefinitions(
                testDefinitionsSource.getText().trim(),
                additionalTestDefinitions.getText().trim(),
                resolver,
                testDefinitionLoadStatus);

        return frame;
    }

    private static ExecutionSummary runWorkbench(AppConfig config) {
        WorkbenchFacade facade = new WorkbenchFacade(
                new DefaultIngestService(),
                new RdfPolicyResolverService(config.useCaseXml(), config.rdfDefinitions()),
                new ClasspathAnnotationTestDiscoveryService(config.implementationPackages()),
                new DefaultTestBindingService(),
                new ParallelPhaseExecutionService(config.threadCount(), new ReflectionExecutionAdapter()),
                new ReportingService(List.of(new SummaryReportExporter(), new XlsCompatibilityExporter())));
        return facade.run(config);
    }

    private static AppConfig buildConfig(
            String dataset,
            String selectedUseCaseId,
            String useCaseSource,
            String testDefinitionsSource,
            String additionalTestDefinitions,
            String ontologySource,
            String discoveryPackages,
            String threads,
            CachedResourceResolver resolver,
            AppConfig defaults) {

        Path useCaseXml = resolver.resolve(useCaseSource, cacheNameFor(useCaseSource));
        Path defaultTestDefinitions = resolver.resolve(testDefinitionsSource, cacheNameFor(testDefinitionsSource));
        Path ontology = resolver.resolve(ontologySource, cacheNameFor(ontologySource));

        List<Path> rdfFiles = new ArrayList<>();
        rdfFiles.add(defaultTestDefinitions);
        rdfFiles.add(ontology);
        for (String extra : splitCsv(additionalTestDefinitions)) {
            rdfFiles.add(resolver.resolve(extra, cacheNameFor(extra)));
        }

        List<String> packages = splitCsv(discoveryPackages);
        if (packages.isEmpty()) {
            packages = defaults.implementationPackages();
        }

        return new AppConfig(
                useCaseXml,
                List.copyOf(rdfFiles),
                Path.of(dataset),
                selectedUseCaseId,
                List.copyOf(packages),
                parseThreads(threads));
    }

    private static int parseThreads(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 1) {
                throw new AppException("Invalid thread count: bdq.threads must be >= 1");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new AppException("Invalid thread count: bdq.threads must be a whole number", e);
        }
    }

    private static String renderPreflightMessage(PreflightState state) {
        int policyResolved = state.plan().tests().size();
        int policyUnresolved = state.plan().unresolvedTests().size();
        int bindingUnresolved = state.binding().unresolved().size();
        int runnable = state.binding().bindings().size();
        int policyTotal = policyResolved + policyUnresolved;

        StringBuilder sb = new StringBuilder();
        sb.append("Use case preflight mapping\n");
        sb.append("Selected use case: ").append(state.plan().useCase().id()).append(" (")
                .append(state.plan().useCase().label()).append(")\n");
        sb.append("Selected use case reference: ").append(state.plan().useCase().policyId()).append('\n');
        sb.append("Policy tests total: ").append(policyTotal).append('\n');
        sb.append("Policy tests resolved from definitions: ").append(policyResolved).append('\n');
        sb.append("Policy tests unresolved in definitions: ").append(policyUnresolved).append('\n');
        sb.append("Discovered implementation methods: ").append(state.discoveredCount()).append('\n');
        sb.append("Runnable mapped tests: ").append(runnable).append('\n');
        sb.append("Tests without discovered implementation: ").append(bindingUnresolved).append("\n\n");

        Map<String, String> labelsByTestId = new LinkedHashMap<>();
        state.plan().tests().forEach(t -> labelsByTestId.put(t.id(), t.label()));
        state.plan().unresolvedTests().forEach(t -> labelsByTestId.put(t.id(), t.label()));
        state.binding().unresolved().forEach(t -> labelsByTestId.put(t.id(), t.label()));

        if (!state.binding().bindings().isEmpty()) {
            sb.append("Matched library mappings:\n");
            state.binding().bindings().forEach(b -> sb.append(" - ")
                    .append(formatTestIdWithLabel(b.testId(), labelsByTestId.get(b.testId())))
                    .append(" -> ")
                    .append(b.implementationClass())
                    .append("#")
                    .append(b.implementationMethod())
                    .append('\n'));
        }
        if (!state.plan().unresolvedTests().isEmpty()) {
            sb.append("Unresolved policy definitions:\n");
            state.plan().unresolvedTests().forEach(t -> sb.append(" - ")
                    .append(formatTestIdWithLabel(t.id(), t.label()))
                    .append('\n'));
        }
        if (!state.binding().unresolved().isEmpty()) {
            sb.append("Unresolved library mappings:\n");
            state.binding().unresolved().forEach(t -> sb.append(" - ")
                    .append(formatTestIdWithLabel(t.id(), t.label()))
                    .append('\n'));
        }

        sb.append("\nNote: multi-record measures need explicit implementation in this framework and are not discovered automatically.\n");
        if (!state.isFullyResolved()) {
            sb.append("You can continue with available tests.\n");
        }
        return sb.toString();
    }

    private static String formatTestIdWithLabel(String id, String label) {
        if (label == null || label.isBlank() || label.equals(id)) {
            return id;
        }
        return id + " (" + label + ")";
    }

    private static void loadUseCasesIntoCombo(
            String source,
            CachedResourceResolver resolver,
            JComboBox<UseCaseChoice> combo,
            JTextArea loadStatus,
            String defaultUseCaseId) {
        combo.removeAllItems();
        try {
            LOG.debug("Loading use cases from source: {}", source);
            Path useCaseXml = resolver.resolve(source, cacheNameFor(source));
            List<UseCase> useCases = UseCaseXmlParser.loadUseCases(useCaseXml).values().stream().toList();
            if (useCases.isEmpty()) {
                throw new AppException("No use cases found in " + useCaseXml);
            }
            UseCaseChoice defaultChoice = null;
            for (UseCase useCase : useCases) {
                UseCaseChoice option = new UseCaseChoice(useCase.id(), useCase.label());
                combo.addItem(option);
                if (defaultChoice == null || useCase.id().equals(defaultUseCaseId)) {
                    defaultChoice = option;
                }
            }
            if (defaultChoice != null) {
                combo.setSelectedItem(defaultChoice);
            }
            LOG.info("Loaded {} use cases from {}", useCases.size(), useCaseXml);
            loadStatus.setText("Loaded " + useCases.size() + " use cases from " + useCaseXml);
        } catch (Exception e) {
            LOG.error("Unable to load use cases from {}", source, e);
            loadStatus.setText("Unable to load use cases: " + e.getMessage());
        }
    }

    private static void loadTestDefinitions(
            String testDefinitionsSource,
            String additionalTestDefinitions,
            CachedResourceResolver resolver,
            JTextArea loadStatus) {
        try {
            List<String> sources = collectDefinitionSources(testDefinitionsSource, additionalTestDefinitions);
            if (sources.isEmpty()) {
                throw new AppException("No test definition sources provided");
            }
            List<Path> resolved = new ArrayList<>();
            for (String source : sources) {
                resolved.add(resolver.resolve(source, cacheNameFor(source)));
            }
            var summary = RdfPolicyResolverService.summarizeDefinitionSources(resolved);
            StringBuilder message = new StringBuilder();
            message.append("Loaded ").append(summary.files().size()).append(" test definition file(s):\n");
            summary.files().forEach(file -> message.append(" - ")
                    .append(file.path())
                    .append(" [use cases: ")
                    .append(file.useCaseCount())
                    .append(", policies: ")
                    .append(file.policyCount())
                    .append(", tests: ")
                    .append(file.testCount())
                    .append("]\n"));
            message.append("Totals [use cases: ")
                    .append(summary.totalUseCases())
                    .append(", policies: ")
                    .append(summary.totalPolicies())
                    .append(", tests: ")
                    .append(summary.totalTests())
                    .append("]");
            loadStatus.setText(message.toString());
            LOG.info("{}", message);
        } catch (Exception e) {
            LOG.error("Unable to load test definitions", e);
            loadStatus.setText("Unable to load test definitions: " + e.getMessage());
        }
    }

    private static List<String> collectDefinitionSources(String testDefinitionsSource, String additionalTestDefinitions) {
        List<String> sources = new ArrayList<>();
        sources.addAll(splitCsv(testDefinitionsSource));
        sources.addAll(splitCsv(additionalTestDefinitions));
        return sources;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String selectedUseCaseId(JComboBox<UseCaseChoice> combo) {
        Object selected = combo.getSelectedItem();
        return selected instanceof UseCaseChoice choice ? choice.id() : "";
    }

    private static String chooseFile(JFrame frame, String title) {
        try {
            FileDialog dialog = new FileDialog((Frame) SwingUtilities.getWindowAncestor(frame), title, FileDialog.LOAD);
            dialog.setVisible(true);
            if (dialog.getFile() != null) {
                return Path.of(dialog.getDirectory(), dialog.getFile()).toString();
            }
        } catch (Exception ignored) {
            // fall through to JFileChooser
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            return chooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    private static int defaultThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return cores <= 1 ? 1 : cores - 1;
    }

    private static void exitApplication(JFrame frame) {
        LOG.info("Shutting down BDQ Workbench GUI");
        frame.dispose();
        System.exit(0);
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

    private static PickerField addPickerField(JPanel panel, JFrame frame, String label, String defaultValue) {
        JPanel row = new JPanel(new BorderLayout(8, 8));
        row.add(new JLabel(label), BorderLayout.WEST);
        JTextField field = new JTextField(defaultValue == null ? "" : defaultValue);
        row.add(field, BorderLayout.CENTER);
        JButton button = new JButton("Browse...");
        row.add(button, BorderLayout.EAST);
        button.addActionListener(e -> {
            String selected = chooseFile(frame, "Select " + label);
            if (selected != null) {
                field.setText(selected);
            }
        });
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        panel.add(row);
        return new PickerField(field, button);
    }

    private static void addComboRow(JPanel panel, String label, JComboBox<UseCaseChoice> combo) {
        JPanel row = new JPanel(new BorderLayout(8, 8));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(combo, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        panel.add(row);
    }

    private static String cacheNameFor(String source) {
        String baseName = "resource";
        try {
            String uriPath = java.net.URI.create(source).getPath();
            if (uriPath != null && !uriPath.isBlank()) {
                baseName = Path.of(uriPath).getFileName().toString();
            }
        } catch (Exception ignored) {
            // source is not a URI, treat as local path
        }
        if ("resource".equals(baseName) && source != null && !source.isBlank()) {
            try {
                baseName = Path.of(source).getFileName().toString();
            } catch (Exception ignored) {
                // keep fallback
            }
        }
        if (baseName.contains(".")) {
            baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        }
        baseName = baseName.toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("(^-+|-+$)", "");
        if (baseName.isBlank()) {
            baseName = "resource";
        }
        int hash = Math.abs(source.hashCode());
        String extension = ".rdf";
        int dot = source.lastIndexOf('.');
        if (dot >= 0 && dot < source.length() - 1) {
            String candidate = source.substring(dot).toLowerCase();
            if (candidate.matches("\\.[a-z0-9]{1,8}")) {
                extension = candidate;
            }
        }
        return baseName + "-cached-" + hash + extension;
    }

    private static void setStatus(JTextArea statusArea, String message) {
        statusArea.setText(message);
        LOG.info("{}", message);
    }

    private static void appendStatus(JTextArea statusArea, String message) {
        statusArea.append(message);
        LOG.info("{}", message);
    }

    private record UseCaseChoice(String id, String label) {
        @Override
        public String toString() {
            return label + " (" + id + ")";
        }
    }

    private record PickerField(JTextField field, JButton button) {
    }

    private record PreflightState(AppConfig config, ExecutionPlan plan, int discoveredCount, TestBindingResult binding) {
        boolean isFullyResolved() {
            return plan.unresolvedTests().isEmpty() && binding.unresolved().isEmpty();
        }
    }
}
