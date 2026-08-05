/** BdqWorkbenchGui.java
 *
 * Swing desktop GUI for the BDQ Workbench: collects startup parameters (dataset, use case,
 * test definitions, discovery packages, thread count), previews the resulting test/binding
 * plan, and runs and monitors execution.
 *
 * Copyright 2026 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.filteredpush.bdq_workbench.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
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
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.TableCellRenderer;
import org.filteredpush.bdq_workbench.execution.ExecutionProgressListener;
import org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionService;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.PreparedRun;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;
import org.filteredpush.bdq_workbench.rdf_policy.UseCaseXmlParser;
import org.filteredpush.bdq_workbench.reporting.DetailedResponseStreamExporter;
import org.filteredpush.bdq_workbench.reporting.RdfResponseExporter;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.reporting.SummaryReportExporter;
import org.filteredpush.bdq_workbench.reporting.TestResultsSummaryService;
import org.filteredpush.bdq_workbench.reporting.XlsxReportExporter;
import org.filteredpush.bdq_workbench.test_discovery.ClasspathAnnotationTestDiscoveryService;
import org.filteredpush.bdq_workbench.test_discovery.DefaultTestBindingService;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Swing desktop presentation layer for the BDQ Workbench.
 *
 * <p>This is a non-instantiable, static-method-only class (see {@link #launch()}) that builds
 * and wires the entire single-window Swing UI: a "setup" card where the user picks a dataset,
 * use case, test definitions, discovery packages, and thread count, and a "monitor" card where
 * the resulting preflight binding review is inspected/edited and the run is started and its
 * progress observed.
 *
 * <p>Internally the class delegates all actual work to {@link WorkbenchFacade}: it builds an
 * {@link org.filteredpush.bdq_workbench.model.AppConfig} from the current UI field values (see
 * {@link #buildConfig}), calls {@link WorkbenchFacade#prepare(AppConfig)} to obtain a
 * {@link PreparedRun} for preflight review, and calls
 * {@link WorkbenchFacade#runPrepared(PreparedRun)} (via {@link #runWorkbench}) to execute it,
 * always off the Swing event dispatch thread using a {@link javax.swing.SwingWorker}. The class
 * has no public API beyond {@link #launch()}; everything else is private, static helper methods
 * that either construct/lay out a piece of the UI or implement the behavior triggered by a
 * button, menu item, or other user action.
 */
final class BdqWorkbenchGui {
    private static final Logger LOG = LoggerFactory.getLogger(BdqWorkbenchGui.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEFAULT_USECASE_SOURCE = "https://bdq.tdwg.org/draft/dist/bdquc.xml";
    private static final String DEFAULT_TEST_DEFINITIONS_SOURCE = "https://bdq.tdwg.org/draft/dist/bdqtest.ttl";
    private static final String DEFAULT_ONTOLOGY_SOURCE = "https://bdq.tdwg.org/draft/vocabulary/bdqffdq.ttl";

    /** Utility class; not instantiable. */
    private BdqWorkbenchGui() {
    }

    /**
     * Application entry point for the desktop GUI. Loads default {@link AppConfig} values (from
     * system properties/environment, via {@link ConfigLoader}), builds the main window on the
     * Swing event dispatch thread, and shows it. Startup failures are logged and reported to the
     * user in a dialog rather than propagated, since there is no console the user is expected to
     * be watching.
     */
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

    /**
     * Builds the main application window: the "setup" card (dataset/use case/advanced options
     * form) and "monitor" card (status log, binding review grid, result summary, and progress
     * bar), swapped via a {@link CardLayout}, plus all button/menu action wiring that connects
     * user input to preflight preparation and execution of the workbench run.
     *
     * @param defaults initial field values (dataset path, use case ID, discovery packages,
     *     thread count) loaded before the window is shown
     * @return the fully constructed, not-yet-visible application frame
     */
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
        installTextAreaClipboardSupport(statusArea);
        JTable bindingGrid = new JTable(new BindingReviewTableModel(List.of()));
        // The grid starts empty (no rows until preflight resolves bindings), so without an
        // explicit preferred size its JScrollPane reports a near-zero preferred height and the
        // split panes below compress it down permanently — it doesn't grow back once rows are
        // added. A fixed viewport size keeps it the visual focus of this page regardless of when
        // (or whether) it's currently populated.
        bindingGrid.setPreferredScrollableViewportSize(new java.awt.Dimension(760, 320));
        JTextArea resultSummaryArea = new JTextArea();
        resultSummaryArea.setEditable(false);
        resultSummaryArea.setLineWrap(true);
        resultSummaryArea.setWrapStyleWord(true);
        installTextAreaClipboardSupport(resultSummaryArea);

        JPanel monitorPanel = new JPanel(new BorderLayout(8, 8));
        JSplitPane bindingGridSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(bindingGrid),
                new JScrollPane(resultSummaryArea));
        bindingGridSplit.setResizeWeight(0.7d);
        JSplitPane monitorSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(statusArea),
                bindingGridSplit);
        monitorSplit.setResizeWeight(0.25d);
        monitorPanel.add(monitorSplit, BorderLayout.CENTER);
        // JSplitPane's initial (pre-realization) divider placement is unreliable when driven
        // purely by preferred sizes, so set it explicitly once the frame is actually showing.
        SwingUtilities.invokeLater(() -> {
            monitorSplit.setDividerLocation(0.25d);
            bindingGridSplit.setDividerLocation(0.7d);
        });
        JProgressBar progress = new JProgressBar();
        progress.setStringPainted(true);
        progress.setVisible(false);
        JLabel monitorHeader = new JLabel("Setup Tests");
        monitorHeader.setFont(monitorHeader.getFont().deriveFont(java.awt.Font.BOLD, monitorHeader.getFont().getSize() + 4f));
        JPanel monitorHeaderPanel = new JPanel(new BorderLayout());
        monitorHeaderPanel.add(monitorHeader, BorderLayout.NORTH);
        monitorHeaderPanel.add(progress, BorderLayout.SOUTH);
        monitorPanel.add(monitorHeaderPanel, BorderLayout.NORTH);

        JPanel monitorControls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton loadParameters = new JButton("Load Parameters...");
        loadParameters.setEnabled(false);
        JButton saveParameters = new JButton("Save Parameters...");
        saveParameters.setEnabled(false);
        JButton backToSetup = new JButton("Back to Select Inputs");
        JButton startRun = new JButton("Start Run");
        startRun.setEnabled(false);
        JButton closeButton = new JButton("Quit");
        monitorControls.add(loadParameters);
        monitorControls.add(saveParameters);
        monitorControls.add(backToSetup);
        monitorControls.add(startRun);
        monitorControls.add(closeButton);
        monitorPanel.add(monitorControls, BorderLayout.SOUTH);

        JPanel setupPanel = new JPanel(new BorderLayout(10, 10));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JLabel setupHeader = new JLabel("Select Inputs");
        setupHeader.setFont(setupHeader.getFont().deriveFont(java.awt.Font.BOLD, setupHeader.getFont().getSize() + 4f));
        JPanel setupHeaderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        setupHeaderRow.add(setupHeader);
        form.add(setupHeaderRow);

        PickerField dataset = addPickerField(form, frame, "Dataset", defaults.datasetPath().toString());

        JComboBox<UseCaseChoice> useCaseChoice = new JComboBox<>();
        addComboRow(form, "Use case", useCaseChoice);
        JTextArea useCaseLoadStatus = new JTextArea(3, 40);
        useCaseLoadStatus.setEditable(false);
        useCaseLoadStatus.setLineWrap(true);
        useCaseLoadStatus.setWrapStyleWord(true);
        useCaseLoadStatus.setBorder(BorderFactory.createEtchedBorder());
        installTextAreaClipboardSupport(useCaseLoadStatus);
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
        installTextAreaClipboardSupport(testDefinitionLoadStatus);
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
        installTextAreaClipboardSupport(setupInfo);
        setupPanel.add(new JScrollPane(setupInfo), BorderLayout.CENTER);

        JPanel setupControls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton run = new JButton("Setup Tests");
        JButton exit = new JButton("Quit");
        setupControls.add(run);
        setupControls.add(exit);
        setupPanel.add(setupControls, BorderLayout.SOUTH);

        cardPanel.add(setupPanel, "setup");
        cardPanel.add(monitorPanel, "monitor");
        root.add(cardPanel, BorderLayout.CENTER);

        final PreflightState[] state = new PreflightState[1];
        installBindingDebugPopup(
                frame,
                bindingGrid,
                state,
                statusArea,
                resultSummaryArea,
                startRun,
                runWithAvailableOnly,
                saveParameters,
                loadParameters,
                monitorHeader);

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
        saveParameters.addActionListener(e -> saveParameterSettings(frame, bindingGrid));
        loadParameters.addActionListener(e -> loadParameterSettings(
                frame,
                bindingGrid,
                state,
                statusArea,
                resultSummaryArea,
                startRun,
                runWithAvailableOnly,
                saveParameters,
                loadParameters,
                monitorHeader));

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
                    PreparedRun preparedRun = createFacade(config).prepare(config);
                    return new PreflightState(preparedRun);
                }

                @Override
                protected void done() {
                    try {
                        updatePreflightUi(
                                state,
                                get().preparedRun(),
                                bindingGrid,
                                statusArea,
                                resultSummaryArea,
                                startRun,
                                runWithAvailableOnly,
                                saveParameters,
                                loadParameters,
                                monitorHeader);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        LOG.error("Preflight mapping failed", cause);
                        setStatus(statusArea, "Failed to prepare run: " + cause.getMessage() + "\n");
                        startRun.setEnabled(false);
                        saveParameters.setEnabled(false);
                        loadParameters.setEnabled(false);
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
            monitorHeader.setText(monitorHeaderText("Run Tests", state[0].preparedRun()));
            progress.setVisible(true);
            progress.setMinimum(0);
            progress.setValue(0);
            appendStatus(statusArea, "\nStarting execution...\n");

            SwingWorker<ExecutionSummary, Void> worker = new SwingWorker<>() {
                @Override
                protected ExecutionSummary doInBackground() {
                    LOG.info("Starting BDQ Workbench execution");
                    BindingReviewTableModel reviewModel = (BindingReviewTableModel) bindingGrid.getModel();
                    PreparedRun editedRun = applyParameterEdits(state[0].preparedRun(), reviewModel);
                    ExecutionProgressTracker tracker = new ExecutionProgressTracker();
                    return runWorkbench(editedRun, tracker, snapshot -> SwingUtilities.invokeLater(() -> {
                        int max = Math.max(1, snapshot.total());
                        progress.setMaximum(max);
                        progress.setValue(snapshot.completed());
                        progress.setString(String.format(
                                "%s %s (%d active threads) queued=%d completed=%d/%d",
                                snapshot.phase(),
                                snapshot.running() > 0 ? "running" : "idle",
                                snapshot.running(),
                                snapshot.queued(),
                                snapshot.completed(),
                                snapshot.total()));
                        resultSummaryArea.setText(renderProgressSnapshot(snapshot));
                    }));
                }

                @Override
                protected void done() {
                    try {
                        ExecutionSummary summary = get();
                        LOG.info("BDQ Workbench execution complete: {} outcomes", summary.responses().size());
                        monitorHeader.setText(monitorHeaderText("Test Results", state[0].preparedRun()));
                        appendStatus(statusArea, "Completed: " + summary.responses().size() + " outcomes\n");
                        resultSummaryArea.setText(renderResultSummary(summary));
                        updateBindingGridExecutionOutputs(bindingGrid, summary);
                        Iterator <Response> i = summary.responses().iterator();
                        while (i.hasNext()) {
							Response r = i.next();
                            String responseText = formatStructuredResponse(r);
							appendStatus(statusArea, String.format(
									" - %s [%s/%s]: %s -> %s (%s)\n",
									r.testId(),
                                    r.phase(),
                                    r.responseStatus(),
									r.recordId(),
									responseText == null ? "(no structured response)" : responseText,
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

    /**
     * Executes {@code preparedRun} via a fresh {@link WorkbenchFacade}, forwarding execution
     * progress events to {@code tracker} and pushing the resulting snapshot to
     * {@code progressConsumer} after every phase-start, response, and phase-completion event so
     * the caller (typically the Start Run button's background worker) can update the progress
     * bar and status text as the run proceeds.
     *
     * @param preparedRun the dataset, plan, and bindings to execute
     * @param tracker accumulates progress events into a displayable {@link ExecutionProgressSnapshot}
     * @param progressConsumer callback invoked with the latest snapshot after each progress event
     * @return the summary of the completed execution
     */
    private static ExecutionSummary runWorkbench(
            PreparedRun preparedRun,
            ExecutionProgressTracker tracker,
            java.util.function.Consumer<ExecutionProgressSnapshot> progressConsumer) {
        WorkbenchFacade facade = createFacade(preparedRun.config(), new ExecutionProgressListener() {
            @Override
            public void onPhaseStarted(org.filteredpush.bdq_workbench.model.Phase phase, int total) {
                tracker.onPhaseStarted(phase, total);
                progressConsumer.accept(tracker.snapshot());
            }

            @Override
            public void onTaskStarted(org.filteredpush.bdq_workbench.model.Phase phase) {
                tracker.onTaskStarted(phase);
                progressConsumer.accept(tracker.snapshot());
            }

            @Override
            public void onTaskFinished(org.filteredpush.bdq_workbench.model.Phase phase) {
                tracker.onTaskFinished(phase);
                progressConsumer.accept(tracker.snapshot());
            }

            @Override
            public void onResponse(org.filteredpush.bdq_workbench.model.Phase phase, Response response, int completed, int total) {
                tracker.onResponse(phase, response, completed, total);
                progressConsumer.accept(tracker.snapshot());
            }

            @Override
            public void onPhaseCompleted(org.filteredpush.bdq_workbench.model.Phase phase, int completed, int total) {
                progressConsumer.accept(tracker.snapshot());
            }
        });
        return facade.runPrepared(preparedRun);
    }

    /**
     * Attaches a right-click popup menu ("Inspect / Run Test", "Set Parameters...", "Show Test
     * Results") to the binding review grid: a mouse listener selects the clicked row, and a
     * {@link PopupMenuListener} disables "Set Parameters..." for tests that do not support
     * parameter editing and "Show Test Results" until a run has produced
     * {@code reports/bdq-report-rdf.ttl} — refreshed in {@code popupMenuWillBecomeVisible} rather
     * than the mouse listener, since {@link javax.swing.JComponent#setComponentPopupMenu}'s
     * automatic display isn't reliably ordered after a plain
     * {@link java.awt.event.MouseListener}'s state changes.
     *
     * @param frame owner frame for dialogs opened from the popup items
     * @param bindingGrid the binding review table the popup is attached to
     * @param state holder for the current {@link PreflightState}, read by the popup actions
     * @param statusArea status log updated after edits made via the popup
     * @param resultSummaryArea result summary area reset after edits made via the popup
     * @param startRun re-enabled/relabeled after parameter edits change resolution status
     * @param runWithAvailableOnly whether the run may proceed with unresolved tests
     * @param saveParameters enabled state refreshed after edits
     * @param loadParameters enabled state refreshed after edits
     * @param monitorHeader the monitor page's title label, passed through to
     *     {@link #openParameterDialog} (reset after edits)
     */
    private static void installBindingDebugPopup(
            JFrame frame,
            JTable bindingGrid,
            PreflightState[] state,
            JTextArea statusArea,
            JTextArea resultSummaryArea,
            JButton startRun,
            JCheckBox runWithAvailableOnly,
            JButton saveParameters,
            JButton loadParameters,
            JLabel monitorHeader) {
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem inspectItem = new JMenuItem("Inspect / Run Test");
        JMenuItem parameterItem = new JMenuItem("Set Parameters...");
        JMenuItem resultsItem = new JMenuItem("Show Test Results");
        popupMenu.add(inspectItem);
        popupMenu.add(parameterItem);
        popupMenu.add(resultsItem);
        inspectItem.addActionListener(e -> openBindingDebugDialog(frame, bindingGrid, state));
        parameterItem.addActionListener(e -> openParameterDialog(
                frame,
                bindingGrid,
                state,
                statusArea,
                resultSummaryArea,
                startRun,
                runWithAvailableOnly,
                saveParameters,
                loadParameters,
                monitorHeader));
        resultsItem.addActionListener(e -> openTestResultsDialog(frame, bindingGrid, state));
        bindingGrid.setComponentPopupMenu(popupMenu);
        bindingGrid.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectPopupRow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                selectPopupRow(e);
            }

            private void selectPopupRow(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = bindingGrid.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    bindingGrid.setRowSelectionInterval(row, row);
                }
            }
        });
        // Menu item enabled state is refreshed here, immediately before the popup is actually
        // shown, rather than in the MouseListener above: JComponent's automatic
        // setComponentPopupMenu display isn't reliably ordered after a plain MouseListener's
        // mutations, so changes made there were not consistently reflected on screen.
        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                int viewRow = bindingGrid.getSelectedRow();
                boolean parameterized = viewRow >= 0
                        && bindingGrid.getModel() instanceof BindingReviewTableModel reviewModel
                        && reviewModel.supportsParameterEditing(bindingGrid.convertRowIndexToModel(viewRow));
                parameterItem.setEnabled(parameterized);
                resultsItem.setEnabled(Files.exists(Path.of("reports", "bdq-report-rdf.ttl")));
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
    }

    /**
     * Handles "Set Parameters..." from the binding grid popup: builds and shows a modal dialog
     * listing the currently selected test's configurable {@code @Parameter} inputs (or an
     * informational dialog if it has none), and on "Apply" stores the entered values (or
     * "use implementation defaults") back into the binding review model, rebinds, and refreshes
     * the preflight UI to reflect the change.
     *
     * @param frame owner frame for the dialog
     * @param bindingGrid the binding review table the selected row is read from
     * @param state holder for the current {@link PreflightState}
     * @param statusArea status log refreshed after applying parameter edits
     * @param resultSummaryArea result summary area refreshed after applying parameter edits
     * @param startRun re-enabled/relabeled after applying parameter edits
     * @param runWithAvailableOnly whether the run may proceed with unresolved tests
     * @param saveParameters enabled state refreshed after applying parameter edits
     * @param loadParameters enabled state refreshed after applying parameter edits
     * @param monitorHeader the monitor page's title label, reset by {@link #updatePreflightUi}
     */
    private static void openParameterDialog(
            JFrame frame,
            JTable bindingGrid,
            PreflightState[] state,
            JTextArea statusArea,
            JTextArea resultSummaryArea,
            JButton startRun,
            JCheckBox runWithAvailableOnly,
            JButton saveParameters,
            JButton loadParameters,
            JLabel monitorHeader) {
        if (state[0] == null || !(bindingGrid.getModel() instanceof BindingReviewTableModel reviewModel)) {
            return;
        }
        int viewRow = bindingGrid.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int row = bindingGrid.convertRowIndexToModel(viewRow);
        BindingReview selectedReview = reviewModel.reviewAt(row);
        PreparedRun editedRun = applyParameterEdits(state[0].preparedRun(), reviewModel);
        ImplementationBinding binding = findBinding(editedRun, selectedReview.test().id());
        List<org.filteredpush.bdq_workbench.model.MethodParameter> configurableParameters =
                configurableParametersFor(editedRun, selectedReview, binding);
        if (configurableParameters.isEmpty()) {
            JOptionPane.showMessageDialog(
                    frame,
                    "No annotated @Parameter inputs are available for this test.",
                    "No Parameters",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        BindingReviewTableModel.ParameterSettings current = reviewModel.settingsFor(selectedReview.test().id());
        JDialog dialog = new JDialog(frame, "Parameters: " + selectedReview.test().label(), true);
        dialog.setLayout(new BorderLayout(8, 8));

        JCheckBox useDefaults = new JCheckBox("Use implementation defaults / no user overrides", current.useDefaults());
        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        Map<String, JTextField> parameterFields = new LinkedHashMap<>();
        for (org.filteredpush.bdq_workbench.model.MethodParameter parameter : configurableParameters) {
            String name = parameter.source();
            JTextField field = addField(fields, name, current.parameters().getOrDefault(name, ""));
            parameterFields.put(name, field);
        }
        setParameterFieldState(parameterFields, !useDefaults.isSelected());
        useDefaults.addActionListener(e -> setParameterFieldState(parameterFields, !useDefaults.isSelected()));

        JButton saveButton = new JButton("Apply");
        JButton cancelButton = new JButton("Cancel");
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        controls.add(saveButton);
        controls.add(cancelButton);

        saveButton.addActionListener(e -> {
            Map<String, String> parameters = new LinkedHashMap<>();
            if (!useDefaults.isSelected()) {
                parameterFields.forEach((name, field) -> {
                    String value = field.getText().trim();
                    if (!value.isEmpty()) {
                        parameters.put(name, value);
                    }
                });
            }
            reviewModel.applyParameterSettings(Map.of(
                    selectedReview.test().id(),
                    new BindingReviewTableModel.ParameterSettings(useDefaults.isSelected(), parameters)));
            PreparedRun rebound = applyParameterEdits(state[0].preparedRun(), reviewModel);
            updatePreflightUi(
                    state,
                    rebound,
                    bindingGrid,
                    statusArea,
                    resultSummaryArea,
                    startRun,
                    runWithAvailableOnly,
                    saveParameters,
                    loadParameters,
                    monitorHeader);
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(useDefaults, BorderLayout.NORTH);
        content.add(new JScrollPane(fields), BorderLayout.CENTER);

        dialog.add(content, BorderLayout.CENTER);
        dialog.add(controls, BorderLayout.SOUTH);
        dialog.setSize(560, 320);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    /**
     * Handles "Inspect / Run Test" from the binding grid popup: builds and shows a non-modal
     * dialog with the selected test's binding details (see {@link #renderBindingReviewDetails})
     * and a "Run Test" button that executes the bound implementation against every input record
     * in isolation (see {@link #runIsolatedBinding}), for tests with a runnable, non-built-in
     * implementation.
     *
     * @param frame owner frame for the dialog
     * @param bindingGrid the binding review table the selected row is read from
     * @param state holder for the current {@link PreflightState}
     */
    private static void openBindingDebugDialog(JFrame frame, JTable bindingGrid, PreflightState[] state) {
        if (state[0] == null || !(bindingGrid.getModel() instanceof BindingReviewTableModel reviewModel)) {
            return;
        }
        int viewRow = bindingGrid.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int row = bindingGrid.convertRowIndexToModel(viewRow);
        BindingReview selectedReview = reviewModel.reviewAt(row);
        PreparedRun editedRun = applyParameterEdits(state[0].preparedRun(), reviewModel);
        BindingReview reboundReview = editedRun.bindingResult().reviews().stream()
                .filter(review -> review.test().id().equals(selectedReview.test().id()))
                .findFirst()
                .orElse(selectedReview);
        ImplementationBinding binding = findBinding(editedRun, reboundReview.test().id());

        JDialog dialog = new JDialog(frame, "Test Debug: " + reboundReview.test().label(), false);
        dialog.setSize(900, 600);
        dialog.setLayout(new BorderLayout(8, 8));

        JTextArea detailsArea = new JTextArea(renderBindingReviewDetails(reboundReview, binding));
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        installTextAreaClipboardSupport(detailsArea);

        JTextArea outputArea = new JTextArea();
        outputArea.setEditable(false);
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);
        installTextAreaClipboardSupport(outputArea);
        boolean runnableInDialog = binding != null && !BuiltInMeasureSpec.isBuiltIn(binding);
        outputArea.setText(binding == null
                ? "No runnable implementation is currently bound for this test.\n"
                : BuiltInMeasureSpec.isBuiltIn(binding)
                        ? "This built-in multi-record measure is evaluated during the full run after matching validation responses are available.\n"
                        : "Use Run Test to execute this binding against each input record in isolation.\n");

        JProgressBar dialogProgress = new JProgressBar(0, Math.max(1, editedRun.dataset().records().size()));
        dialogProgress.setStringPainted(true);
        dialogProgress.setVisible(runnableInDialog);
        dialogProgress.setValue(0);
        dialogProgress.setString(binding == null
                ? "No runnable binding"
                : runnableInDialog
                        ? "0/" + editedRun.dataset().records().size()
                        : "Built-in aggregate measure");
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton runButton = new JButton("Run Test");
        runButton.setEnabled(runnableInDialog);
        JButton closeButton = new JButton("Close");
        controls.add(runButton);
        controls.add(closeButton);

        runButton.addActionListener(e -> runIsolatedBinding(dialog, editedRun, binding, outputArea, dialogProgress, runButton));
        closeButton.addActionListener(e -> dialog.dispose());

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(detailsArea),
                new JScrollPane(outputArea));
        splitPane.setResizeWeight(0.35d);

        dialog.add(dialogProgress, BorderLayout.NORTH);
        dialog.add(splitPane, BorderLayout.CENTER);
        dialog.add(controls, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    /**
     * Handles "Show Test Results" from the binding grid popup: builds a
     * {@link TestResultsSummaryService} over the run's RDF definitions, use case source, and
     * exported results ({@code reports/bdq-report-rdf.ttl}), and shows the selected test's
     * {@link TestResultsSummaryService#summarize(String, TestType) summary} — what the test does
     * (from its ratified definition) and how it performed on this run's input, broken out by
     * phase — in a non-modal, read-only dialog.
     *
     * @param frame owner frame for the dialog and any error/info dialog
     * @param bindingGrid the binding review table the selected row is read from
     * @param state holder for the current {@link PreflightState}, used for its
     *     {@link PreparedRun#config()} (RDF definitions and use case source)
     */
    private static void openTestResultsDialog(JFrame frame, JTable bindingGrid, PreflightState[] state) {
        if (state[0] == null || !(bindingGrid.getModel() instanceof BindingReviewTableModel reviewModel)) {
            return;
        }
        int viewRow = bindingGrid.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int row = bindingGrid.convertRowIndexToModel(viewRow);
        TestDefinition test = reviewModel.reviewAt(row).test();

        Path resultsPath = Path.of("reports", "bdq-report-rdf.ttl");
        if (Files.notExists(resultsPath)) {
            JOptionPane.showMessageDialog(
                    frame,
                    "Run the tests first to see results for this test.",
                    "No Results Yet",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        AppConfig config = state[0].preparedRun().config();
        List<Path> rdfSources = new ArrayList<>(config.rdfDefinitions());
        rdfSources.add(config.useCaseXml());
        rdfSources.add(resultsPath);
        String summary = new TestResultsSummaryService(rdfSources).summarize(test.id(), test.type());

        JDialog dialog = new JDialog(frame, "Test Results: " + test.label(), false);
        dialog.setSize(760, 560);
        dialog.setLayout(new BorderLayout(8, 8));

        JTextArea summaryArea = new JTextArea(summary);
        summaryArea.setEditable(false);
        summaryArea.setLineWrap(true);
        summaryArea.setWrapStyleWord(true);
        installTextAreaClipboardSupport(summaryArea);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dialog.dispose());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        controls.add(closeButton);

        dialog.add(new JScrollPane(summaryArea), BorderLayout.CENTER);
        dialog.add(controls, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    /**
     * Handles the "Run Test" button of the test debug dialog: runs the given binding's
     * implementation against every record of {@code preparedRun}'s dataset, one at a time, on a
     * background {@link SwingWorker}, streaming a rendered {@link ReflectionExecutionAdapter.ExecutionTrace}
     * for each record to {@code outputArea} and advancing {@code progressBar} as records complete.
     *
     * @param dialog the debug dialog, brought back to front once the run completes
     * @param preparedRun supplies the dataset records to execute against
     * @param binding the implementation binding to invoke
     * @param outputArea receives the per-record execution trace text
     * @param progressBar advanced as records are completed
     * @param runButton disabled while running, re-enabled on completion
     */
    private static void runIsolatedBinding(
            JDialog dialog,
            PreparedRun preparedRun,
            ImplementationBinding binding,
            JTextArea outputArea,
            JProgressBar progressBar,
            JButton runButton) {
        DiscoveredImplementation implementation;
        try {
            implementation = findImplementation(preparedRun, binding);
        } catch (AppException e) {
            outputArea.setText("Unable to locate implementation for isolated execution: " + e.getMessage() + "\n");
            progressBar.setVisible(false);
            dialog.toFront();
            return;
        }
        ReflectionExecutionAdapter adapter = new ReflectionExecutionAdapter();
        List<org.filteredpush.bdq_workbench.model.CanonicalRecord> records = preparedRun.dataset().copy().records();
        outputArea.setText("Running " + binding.testId() + " against " + records.size() + " input record(s).\n\n");
        runButton.setEnabled(false);
        progressBar.setMaximum(Math.max(1, records.size()));
        progressBar.setValue(0);
        progressBar.setString("0/" + records.size());

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                int completed = 0;
                for (org.filteredpush.bdq_workbench.model.CanonicalRecord record : records) {
                    ReflectionExecutionAdapter.ExecutionTrace trace =
                            adapter.executeWithTrace(record, binding, implementation);
                    completed++;
                    publish(renderExecutionTrace(trace, completed, records.size()));
                    setProgress((int) Math.round((completed * 100.0d) / Math.max(1, records.size())));
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    outputArea.append(chunk);
                    outputArea.append("\n");
                }
                int processed = Math.min(records.size(), progressBar.getValue() + chunks.size());
                progressBar.setValue(processed);
                progressBar.setString(processed + "/" + records.size());
                outputArea.setCaretPosition(outputArea.getDocument().getLength());
            }

            @Override
            protected void done() {
                runButton.setEnabled(true);
                progressBar.setValue(records.size());
                progressBar.setString(records.size() + "/" + records.size());
                outputArea.append("Isolated test run complete.\n");
                outputArea.setCaretPosition(outputArea.getDocument().getLength());
                dialog.toFront();
            }
        };
        worker.execute();
    }

    /**
     * Builds a {@link WorkbenchFacade} for {@code config} with no progress reporting, used for
     * preflight preparation where execution progress is not relevant.
     *
     * @param config application configuration to wire the facade's services from
     * @return a facade ready to prepare or run {@code config}
     */
    private static WorkbenchFacade createFacade(AppConfig config) {
        return createFacade(config, new ExecutionProgressListener() {
        });
    }

    /**
     * Builds a {@link WorkbenchFacade} wired with the standard set of services (ingest, RDF
     * policy resolution, classpath test discovery, default test binding, parallel-phase
     * execution, and the summary/detailed/xls-compatibility/rdf report exporters) for
     * {@code config}.
     *
     * @param config application configuration specifying RDF sources, dataset, discovery
     *     packages, and thread count
     * @param progressListener notified of phase/response progress during execution
     * @return a facade ready to prepare and run {@code config}
     */
    private static WorkbenchFacade createFacade(AppConfig config, ExecutionProgressListener progressListener) {
        return new WorkbenchFacade(
                new DefaultIngestService(),
                new RdfPolicyResolverService(config.useCaseXml(), config.rdfDefinitions()),
                new ClasspathAnnotationTestDiscoveryService(config.implementationPackages()),
                new DefaultTestBindingService(),
                new ParallelPhaseExecutionService(config.threadCount(), new ReflectionExecutionAdapter(), progressListener, config.dedupEnabled()),
                new ReportingService(List.of(
                        new SummaryReportExporter(),
                        new DetailedResponseStreamExporter(),
                        new XlsxReportExporter(),
                        new RdfResponseExporter(config.rdfDefinitions()))));
    }

    /**
     * Assembles an {@link AppConfig} from the current setup form field values: resolves (and
     * caches locally, via {@code resolver}) the use case, test definitions, and ontology sources,
     * appends any additional test definition sources, and falls back to {@code defaults}'
     * discovery packages when none are specified.
     *
     * @param dataset dataset file path field value
     * @param selectedUseCaseId ID of the use case chosen in the combo box
     * @param useCaseSource use case RDF file/URL field value
     * @param testDefinitionsSource primary test definitions file/URL field value
     * @param additionalTestDefinitions comma-separated extra test definition files/URLs
     * @param ontologySource BDQ FFDQ ontology file/URL field value
     * @param discoveryPackages comma-separated implementation discovery packages
     * @param threads thread count field value
     * @param resolver resolves and caches remote/local resource paths
     * @param defaults fallback values (discovery packages, and the dedup-execution setting, which
     *     has no dedicated form field yet) used when a field is blank
     * @return the assembled configuration, ready for {@link WorkbenchFacade#prepare(AppConfig)}
     */
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
                parseThreads(threads),
                defaults.dedupEnabled());
    }

    /**
     * Parses the thread-count field value, rejecting non-numeric or non-positive values.
     *
     * @param raw the thread count field's text
     * @return the parsed thread count
     * @throws AppException if {@code raw} is not a whole number or is less than 1
     */
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

    /**
     * Renders the human-readable preflight summary shown in the status area after a "Setup
     * Tests" run: selected use case, policy/binding resolution counts, matched library mappings,
     * and any unresolved policy definitions or library mappings.
     *
     * @param state the completed preflight result to summarize
     * @return the multi-line preflight summary text
     */
    private static String renderPreflightMessage(PreflightState state) {
        int policyResolved = state.preparedRun().plan().tests().size();
        int policyUnresolved = state.preparedRun().plan().unresolvedTests().size();
        int bindingUnresolved = state.preparedRun().bindingResult().unresolved().size();
        int runnable = state.preparedRun().bindingResult().bindings().size();
        int policyTotal = policyResolved + policyUnresolved;

        StringBuilder sb = new StringBuilder();
        sb.append("Use case preflight mapping\n");
        sb.append("Selected use case: ").append(state.preparedRun().plan().useCase().id()).append(" (")
                .append(state.preparedRun().plan().useCase().label()).append(")\n");
        sb.append("Selected use case reference: ").append(state.preparedRun().plan().useCase().policyId()).append('\n');
        sb.append("Policy tests total: ").append(policyTotal).append('\n');
        sb.append("Policy tests resolved from definitions: ").append(policyResolved).append('\n');
        sb.append("Policy tests unresolved in definitions: ").append(policyUnresolved).append('\n');
        sb.append("Discovered implementation methods: ").append(state.preparedRun().discovered().size()).append('\n');
        sb.append("Runnable mapped tests: ").append(runnable).append('\n');
        sb.append("Tests without discovered implementation: ").append(bindingUnresolved).append("\n\n");

        Map<String, String> labelsByTestId = new LinkedHashMap<>();
        state.preparedRun().plan().tests().forEach(t -> labelsByTestId.put(t.id(), t.label()));
        state.preparedRun().plan().unresolvedTests().forEach(t -> labelsByTestId.put(t.id(), t.label()));
        state.preparedRun().bindingResult().unresolved().forEach(t -> labelsByTestId.put(t.id(), t.label()));

        if (!state.preparedRun().bindingResult().bindings().isEmpty()) {
            sb.append("Matched library mappings:\n");
            state.preparedRun().bindingResult().bindings().forEach(b -> sb.append(" - ")
                    .append(formatTestIdWithLabel(b.testId(), labelsByTestId.get(b.testId())))
                    .append(" -> ")
                    .append(b.implementationClass())
                    .append("#")
                    .append(b.implementationMethod())
                    .append(" [")
                    .append(b.bindingStatus())
                    .append(", ")
                    .append(b.methodSelection())
                    .append("]")
                    .append('\n'));
        }
        if (!state.preparedRun().plan().unresolvedTests().isEmpty()) {
            sb.append("Unresolved policy definitions:\n");
            state.preparedRun().plan().unresolvedTests().forEach(t -> sb.append(" - ")
                    .append(formatTestIdWithLabel(t.id(), t.label()))
                    .append('\n'));
        }
        if (!state.preparedRun().bindingResult().unresolved().isEmpty()) {
            sb.append("Unresolved library mappings:\n");
            state.preparedRun().bindingResult().unresolved().forEach(t -> sb.append(" - ")
                    .append(formatTestIdWithLabel(t.id(), t.label()))
                    .append('\n'));
        }

        sb.append("\nNote: COUNT-based multi-record measures are synthesized from validation response streams; other multi-record measures still need explicit implementation.\n");
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

    /**
     * Handles "Load use cases" (and the initial load at startup): resolves {@code source} to a
     * local file (caching remote resources via {@code resolver}), parses its use cases, repopulates
     * {@code combo} with one entry per use case, selects {@code defaultUseCaseId} if present
     * (otherwise the first entry), and reports the outcome in {@code loadStatus}.
     *
     * @param source use case RDF file path or URL
     * @param resolver resolves and caches remote/local resource paths
     * @param combo the use case selection combo box, repopulated on success
     * @param loadStatus status text area updated with the load result or error
     * @param defaultUseCaseId use case ID to preselect if present among the loaded use cases
     */
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

    /**
     * Handles "Load tests" (and the initial load at startup): resolves the primary and any
     * additional test definition sources to local files (caching remote resources via
     * {@code resolver}), summarizes each file's use case/policy/test counts via
     * {@link RdfPolicyResolverService#summarizeDefinitionSources}, and reports the per-file and
     * total counts (or an error) in {@code loadStatus}.
     *
     * @param testDefinitionsSource primary test definitions file/URL field value
     * @param additionalTestDefinitions comma-separated extra test definition files/URLs
     * @param resolver resolves and caches remote/local resource paths
     * @param loadStatus status text area updated with the load result or error
     */
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

    /**
     * Prompts the user to pick a file to open, preferring the native AWT {@link FileDialog} and
     * falling back to a {@link JFileChooser} if the native dialog cannot be used.
     *
     * @param frame owner frame for the dialog
     * @param title dialog title
     * @return the selected file's absolute path, or {@code null} if the user cancelled
     */
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

    /**
     * Prompts the user to pick a destination file to save to, preferring the native AWT
     * {@link FileDialog} and falling back to a {@link JFileChooser} if the native dialog cannot
     * be used.
     *
     * @param frame owner frame for the dialog
     * @param title dialog title
     * @param defaultFileName suggested file name
     * @return the selected destination file's absolute path, or {@code null} if the user cancelled
     */
    private static String chooseSaveFile(JFrame frame, String title, String defaultFileName) {
        try {
            FileDialog dialog = new FileDialog((Frame) SwingUtilities.getWindowAncestor(frame), title, FileDialog.SAVE);
            dialog.setFile(defaultFileName);
            dialog.setVisible(true);
            if (dialog.getFile() != null) {
                return Path.of(dialog.getDirectory(), dialog.getFile()).toString();
            }
        } catch (Exception ignored) {
            // fall through to JFileChooser
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(new java.io.File(defaultFileName));
        int result = chooser.showSaveDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            return chooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    private static int defaultThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return cores <= 1 ? 1 : cores - 1;
    }

    /**
     * Handles the "Quit" buttons: disposes the main frame and terminates the JVM.
     *
     * @param frame the main application frame to dispose
     */
    private static void exitApplication(JFrame frame) {
        LOG.info("Shutting down BDQ Workbench GUI");
        frame.dispose();
        System.exit(0);
    }

    /**
     * Builds a labeled text field row (label west, field center) and appends it to {@code panel}.
     *
     * @param panel panel the row is added to
     * @param label label text shown to the left of the field
     * @param defaultValue initial field text, or {@code ""} if {@code null}
     * @return the created text field
     */
    private static JTextField addField(JPanel panel, String label, String defaultValue) {
        JPanel row = new JPanel(new BorderLayout(8, 8));
        row.add(new JLabel(label), BorderLayout.WEST);
        JTextField field = new JTextField(defaultValue == null ? "" : defaultValue);
        row.add(field, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        panel.add(row);
        return field;
    }

    /**
     * Builds a labeled text field row with a trailing "Browse..." button (label west, field
     * center, button east) that opens a file chooser and populates the field, and appends it to
     * {@code panel}.
     *
     * @param panel panel the row is added to
     * @param frame owner frame for the file chooser dialog
     * @param label label text shown to the left of the field, also used as the chooser's title
     * @param defaultValue initial field text, or {@code ""} if {@code null}
     * @return the created field and browse button, paired for later reference
     */
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

    /**
     * Builds a labeled combo box row (label west, combo center) and appends it to {@code panel}.
     *
     * @param panel panel the row is added to
     * @param label label text shown to the left of the combo box
     * @param combo the combo box to place in the row
     */
    private static void addComboRow(JPanel panel, String label, JComboBox<UseCaseChoice> combo) {
        JPanel row = new JPanel(new BorderLayout(8, 8));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(combo, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        panel.add(row);
    }

    /**
     * Derives a stable, filesystem-safe cache file name for a resource source (URL or local
     * path): extracts the base file name (from the URI path if {@code source} parses as a URI,
     * otherwise from the local path), strips its extension, sanitizes it to lowercase
     * alphanumerics/{@code ._-}, and appends a hash of the full source plus the original (or a
     * default {@code .rdf}) extension so distinct sources with the same base name don't collide.
     *
     * @param source the resource URL or local path to derive a cache name for
     * @return the cache file name, e.g. {@code "bdqtest-cached-12345.ttl"}
     */
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

    /**
     * Rebuilds the {@link PreparedRun}'s test bindings after user parameter edits: replaces each
     * test's parameters with the values currently held in {@code reviewModel} (or its original
     * parameters if the model has no override), then re-runs {@link DefaultTestBindingService}
     * against the (unchanged) discovered implementations. Used after every parameter edit and
     * before starting execution, so the run always reflects the latest edits.
     *
     * @param preparedRun the run whose plan/discovered implementations are re-bound
     * @param reviewModel holds the current per-test parameter settings from the binding grid
     * @return a new {@link PreparedRun} with re-bound bindings, otherwise unchanged
     */
    private static PreparedRun applyParameterEdits(PreparedRun preparedRun, BindingReviewTableModel reviewModel) {
        List<TestDefinition> updatedTests = preparedRun.plan().tests().stream()
                .map(test -> new TestDefinition(
                        test.id(),
                        test.label(),
                        test.type(),
                        test.phase(),
                        parameterValuesFor(test, reviewModel.settingsFor(test.id())),
                        test.metadata()))
                .toList();
        TestBindingResult rebound = new DefaultTestBindingService().bind(
                updatedTests,
                preparedRun.discovered(),
                Map.of(),
                collectAvailableTerms(preparedRun.dataset()));
        return new PreparedRun(
                preparedRun.config(),
                preparedRun.dataset().copy(),
                preparedRun.plan(),
                preparedRun.discovered(),
                rebound);
    }

    private static Map<String, String> parameterValuesFor(
            TestDefinition test,
            BindingReviewTableModel.ParameterSettings settings) {
        if (settings.useDefaults()) {
            return Map.of();
        }
        return settings.parameters().isEmpty() ? test.parameters() : settings.parameters();
    }

    private static ImplementationBinding findBinding(PreparedRun preparedRun, String testId) {
        return preparedRun.bindingResult().bindings().stream()
                .filter(binding -> binding.testId().equals(testId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Looks up the {@link DiscoveredImplementation} matching a binding's implementation class and
     * method, for isolated (single-test debug) execution.
     *
     * @param preparedRun supplies the discovered implementations to search
     * @param binding the binding whose implementation is being looked up
     * @return the matching discovered implementation
     * @throws AppException if no discovered implementation matches
     */
    private static DiscoveredImplementation findImplementation(PreparedRun preparedRun, ImplementationBinding binding) {
        return preparedRun.discovered().stream()
                .filter(discovered -> discovered.implementationClass().equals(binding.implementationClass())
                        && discovered.implementationMethod().equals(binding.implementationMethod()))
                .findFirst()
                .orElseThrow(() -> new AppException("No discovered implementation found for "
                        + binding.implementationClass() + "#" + binding.implementationMethod()));
    }

    private static java.util.Set<String> collectAvailableTerms(org.filteredpush.bdq_workbench.model.RecordDataset dataset) {
        java.util.Set<String> terms = new java.util.LinkedHashSet<>();
        dataset.records().forEach(record -> terms.addAll(record.terms().keySet()));
        return terms;
    }

    /**
     * Renders an {@link ExecutionProgressSnapshot} as the multi-line text shown in the result
     * summary area while a run is in progress.
     *
     * @param snapshot the current execution progress
     * @return the rendered progress text
     */
    private static String renderProgressSnapshot(ExecutionProgressSnapshot snapshot) {
        return "Execution progress\n"
                + "Phase: " + snapshot.phase() + "\n"
                + "Queued: " + snapshot.queued() + "\n"
                + "Status: " + (snapshot.running() > 0 ? "running" : "idle")
                + " (" + snapshot.running() + " active thread(s))\n"
                + "Completed: " + snapshot.completed() + "/" + snapshot.total() + "\n"
                + "Status counts: " + snapshot.statusCounts() + "\n"
                + "Result counts: " + snapshot.resultCounts() + "\n";
    }

    /**
     * Renders the final result summary text shown after a run completes: the standard summary
     * report text plus a note of the saved report file names.
     *
     * @param summary the completed execution's summary
     * @return the rendered result summary text
     */
    private static String renderResultSummary(ExecutionSummary summary) {
        return SummaryReportExporter.renderSummaryText("Results summary", summary)
                + "Saved files: reports/bdq-report-summary.txt, reports/bdq-report-responses.txt, reports/bdq-report-xls.xlsx, reports/bdq-report-rdf.ttl\n";
    }

    /**
     * After a run completes, pushes each multi-record MEASURE test's pre-/post-amendment results
     * into the binding review grid's execution output columns, so the grid shows what each
     * measure computed without requiring the user to open the debug dialog.
     *
     * @param bindingGrid the binding review table to update
     * @param summary the completed execution's summary, supplying multi-record measure responses
     */
    private static void updateBindingGridExecutionOutputs(JTable bindingGrid, ExecutionSummary summary) {
        if (!(bindingGrid.getModel() instanceof BindingReviewTableModel reviewModel)) {
            return;
        }
        Map<String, BindingReviewTableModel.PhaseExecutionOutput> outputs = new LinkedHashMap<>();
        summary.multiRecordMeasureResponses().stream()
                .filter(response -> response.testType() == TestType.MEASURE)
                .collect(java.util.stream.Collectors.groupingBy(
                        Response::testId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .forEach((testId, responses) -> {
                    Map<Phase, Response> byPhase = responses.stream().collect(java.util.stream.Collectors.toMap(
                            Response::phase,
                            response -> response,
                            (left, right) -> right,
                            LinkedHashMap::new));
                    outputs.put(testId, new BindingReviewTableModel.PhaseExecutionOutput(
                            formatMeasureGridOutput(byPhase.get(Phase.PRE_AMENDMENT)),
                            formatMeasureGridOutput(byPhase.get(Phase.POST_AMENDMENT))));
                });
        reviewModel.applyExecutionOutputs(outputs);
    }

    /**
     * Formats a single multi-record measure response for display in the binding grid: for
     * COUNT-kind built-in measures, renders {@code "<count> (<percentage>%)"} (or just the count
     * if no percentage is available); otherwise falls back to the raw response result, or a
     * structured status/result rendering if the result is blank.
     *
     * @param response the measure response to format, or {@code null} if none was produced
     * @return the formatted display text, or {@code ""} if {@code response} is {@code null}
     */
    private static String formatMeasureGridOutput(Response response) {
        if (response == null) {
            return "";
        }
        String kind = response.parameters().get(BuiltInMeasureSpec.KIND_KEY);
        if ("COUNT".equals(kind)) {
            String count = response.parameters().getOrDefault(BuiltInMeasureSpec.MATCHING_COUNT_KEY, response.responseResult());
            String percentage = response.parameters().get(BuiltInMeasureSpec.PERCENTAGE_KEY);
            return percentage == null || percentage.isBlank()
                    ? count
                    : count + " (" + percentage + "%)";
        }
        return response.responseResult() == null || response.responseResult().isBlank()
                ? formatStructuredResponse(response)
                : response.responseResult();
    }

    /**
     * Renders the details panel of the test debug dialog: the selected test's identity, binding
     * and implementation status, parameterization capability, chosen method, parameter values,
     * diagnostics, and (if a binding was found) its resolved per-parameter argument bindings.
     *
     * @param review the selected test's binding review
     * @param binding the resolved implementation binding, or {@code null} if none is bound
     * @return the rendered details text
     */
    private static String renderBindingReviewDetails(BindingReview review, ImplementationBinding binding) {
        StringBuilder sb = new StringBuilder();
        sb.append("Selected test\n");
        sb.append("Label: ").append(review.test().label()).append('\n');
        sb.append("Id: ").append(review.test().id()).append('\n');
        sb.append("Type: ").append(review.test().type()).append('\n');
        sb.append("Implementation status: ").append(review.implementationStatus()).append('\n');
        sb.append("Binding status: ").append(review.bindingStatus()).append('\n');
        sb.append("Parameterization: ").append(describeParameterization(review.parameterizationCapability())).append('\n');
        sb.append("Chosen method: ").append(review.chosenImplementationMethod()).append('\n');
        sb.append("Use defaults: ").append(review.usingDefaultParameters()).append('\n');
        sb.append("Parameter values: ").append(review.parameterValues()).append("\n\n");
        sb.append("Diagnostics:\n");
        review.diagnostics().forEach(diagnostic -> sb.append(" - ").append(diagnostic).append('\n'));
        if (binding != null) {
            sb.append("\nResolved parameter bindings:\n");
            binding.parameterBindings().forEach(parameter -> sb.append(" - ")
                    .append(parameter.parameter().name())
                    .append(" [")
                    .append(parameter.parameter().role())
                    .append("] from ")
                    .append(parameter.resolvedSource())
                    .append(" :: ")
                    .append(parameter.reason())
                    .append('\n'));
        }
        return sb.toString();
    }

    /**
     * Renders one record's isolated-execution trace for the test debug dialog's output area: the
     * record position/ID, each parameter binding's source/raw/converted values and reasoning, the
     * raw method return type/value, the structured response, and any comment or amendments.
     *
     * @param trace the reflection execution adapter's trace of a single record's execution
     * @param index the 1-based position of this record among those being run
     * @param total the total number of records being run
     * @return the rendered trace text for this record
     */
    private static String renderExecutionTrace(
            ReflectionExecutionAdapter.ExecutionTrace trace,
            int index,
            int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("Record ").append(index).append('/').append(total).append(": ")
                .append(trace.response().recordId()).append('\n');
        sb.append("Bindings:\n");
        trace.argumentTraces().forEach(argument -> sb.append(" - ")
                .append(argument.parameterName())
                .append(" [")
                .append(argument.role())
                .append("] source=")
                .append(argument.source())
                .append(", raw=")
                .append(argument.rawValue())
                .append(", converted=")
                .append(argument.convertedValue())
                .append(", note=")
                .append(argument.reason())
                .append('\n'));
        sb.append("Raw return type: ").append(trace.rawReturnType()).append('\n');
        sb.append("Raw return value: ").append(trace.rawReturnValue()).append('\n');
        String responseText = formatStructuredResponse(trace.response());
        if (responseText != null) {
            sb.append("Response: ").append(responseText).append('\n');
        }
        if (trace.response().comment() != null) {
            sb.append("Comment: ").append(trace.response().comment()).append('\n');
        }
        if (!trace.response().amendments().isEmpty()) {
            sb.append("Amendments: ").append(trace.response().amendments()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Builds the monitor page's title text: the given phase name, plus {@code ": <use case
     * label>"} when {@code preparedRun}'s use case has a non-blank label.
     *
     * @param phase the current phase name ({@code "Setup Tests"}, {@code "Run Tests"}, or
     *     {@code "Test Results"})
     * @param preparedRun the run whose selected use case's label is appended, if any
     * @return the title text to show in the monitor header label
     */
    private static String monitorHeaderText(String phase, PreparedRun preparedRun) {
        String useCaseLabel = preparedRun == null ? null : preparedRun.plan().useCase().label();
        return useCaseLabel == null || useCaseLabel.isBlank() ? phase : phase + ": " + useCaseLabel;
    }

    /**
     * Applies a newly prepared (or re-bound) run to the monitor UI: stores it as the current
     * {@link PreflightState}, renders the preflight summary into {@code statusArea}, replaces the
     * binding grid's model with the new bindings, resets the result summary area, and
     * enables/labels the "Start Run"/"Start Available Tests" button and the save/load parameter
     * buttons according to whether every test resolved and bound successfully.
     *
     * @param state holder for the current {@link PreflightState}, replaced with one wrapping
     *     {@code preparedRun}
     * @param preparedRun the freshly prepared or re-bound run to display
     * @param bindingGrid the binding review table, given a new model
     * @param statusArea status log updated with the preflight summary
     * @param resultSummaryArea result summary area reset to the "ready to review" message
     * @param startRun enabled/labeled according to resolution completeness
     * @param runWithAvailableOnly whether the run may proceed with unresolved tests
     * @param saveParameters enabled if the run has any binding reviews
     * @param loadParameters enabled if the run has any binding reviews
     * @param monitorHeader the monitor page's title label, reset to {@code "Setup Tests"} (plus
     *     the selected use case's label, see {@link #monitorHeaderText}) since this method is only
     *     called while reviewing/editing bindings, before a run has started
     */
    private static void updatePreflightUi(
            PreflightState[] state,
            PreparedRun preparedRun,
            JTable bindingGrid,
            JTextArea statusArea,
            JTextArea resultSummaryArea,
            JButton startRun,
            JCheckBox runWithAvailableOnly,
            JButton saveParameters,
            JButton loadParameters,
            JLabel monitorHeader) {
        state[0] = new PreflightState(preparedRun);
        monitorHeader.setText(monitorHeaderText("Setup Tests", preparedRun));
        LOG.debug("Preflight mapping complete: {} runnable, {} unresolved",
                state[0].preparedRun().bindingResult().bindings().size(),
                state[0].preparedRun().bindingResult().unresolved().size());
        setStatus(statusArea, renderPreflightMessage(state[0]));
        bindingGrid.setModel(new BindingReviewTableModel(state[0].preparedRun().bindingResult().reviews()));
        configureBindingGrid(bindingGrid);
        resultSummaryArea.setText("Parameter review ready. Edit parameter values, use the row popup, or save/load settings before starting the run.");
        boolean complete = state[0].isFullyResolved();
        if (!complete && !runWithAvailableOnly.isSelected()) {
            appendStatus(statusArea, "\nRun is blocked until unresolved tests are handled.\n");
            startRun.setEnabled(false);
        } else {
            startRun.setText(complete ? "Start Run" : "Start Available Tests");
            startRun.setEnabled(true);
        }
        boolean hasReviews = !preparedRun.bindingResult().reviews().isEmpty();
        saveParameters.setEnabled(hasReviews);
        loadParameters.setEnabled(hasReviews);
    }

    /**
     * Attaches a right-click popup menu ("Copy", "Select All") to a read-only text area, since
     * plain {@link JTextArea}s have no built-in context menu.
     *
     * @param textArea the text area to attach the popup menu to
     */
    private static void installTextAreaClipboardSupport(JTextArea textArea) {
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> textArea.copy());
        JMenuItem selectAllItem = new JMenuItem("Select All");
        selectAllItem.addActionListener(e -> textArea.selectAll());
        popupMenu.add(copyItem);
        popupMenu.add(selectAllItem);
        textArea.setComponentPopupMenu(popupMenu);
    }

    /**
     * Installs a custom cell renderer on the binding grid's "supports parameter editing" boolean
     * column so that rows for tests which do not support parameter editing render an empty cell
     * instead of a (misleading, always-false) checkbox.
     *
     * @param bindingGrid the binding review table to configure
     */
    private static void configureBindingGrid(JTable bindingGrid) {
        TableCellRenderer booleanRenderer = bindingGrid.getDefaultRenderer(Boolean.class);
        TableCellRenderer textRenderer = bindingGrid.getDefaultRenderer(Object.class);
        bindingGrid.getColumnModel().getColumn(6).setCellRenderer((table, value, isSelected, hasFocus, row, column) -> {
            if (table.getModel() instanceof BindingReviewTableModel reviewModel
                    && !reviewModel.supportsParameterEditing(table.convertRowIndexToModel(row))) {
                return textRenderer.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
            }
            return booleanRenderer.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        });
    }

    /**
     * Determines which {@code @Parameter}-role method parameters should be offered for editing
     * in the "Set Parameters..." dialog for a given test: prefers the parameters already selected
     * for the current binding, and otherwise falls back to the union of parameters across all
     * discovered, parameterized implementations that match the test's identifier.
     *
     * @param preparedRun supplies the discovered implementations to search when there is no
     *     current binding
     * @param review the selected test's binding review, used to match candidate implementations
     * @param binding the current implementation binding, or {@code null} if none is bound
     * @return the configurable parameters to show in the dialog, possibly empty
     */
    private static List<org.filteredpush.bdq_workbench.model.MethodParameter> configurableParametersFor(
            PreparedRun preparedRun,
            BindingReview review,
            ImplementationBinding binding) {
        List<org.filteredpush.bdq_workbench.model.MethodParameter> selectedBindingParameters = binding == null
                ? List.of()
                : binding.parameterBindings().stream()
                        .map(org.filteredpush.bdq_workbench.model.BoundMethodParameter::parameter)
                        .filter(parameter -> parameter.role() == org.filteredpush.bdq_workbench.model.ParameterRole.PARAMETER)
                        .toList();
        if (!selectedBindingParameters.isEmpty()) {
            return selectedBindingParameters;
        }
        Map<String, org.filteredpush.bdq_workbench.model.MethodParameter> discoveredParameters = new LinkedHashMap<>();
        preparedRun.discovered().stream()
                .filter(DiscoveredImplementation::isParameterized)
                .filter(discovered -> matchesTestIdentifier(review.test().id(), discovered))
                .sorted(java.util.Comparator.comparing(DiscoveredImplementation::implementationClass)
                        .thenComparing(DiscoveredImplementation::implementationMethod))
                .flatMap(discovered -> discovered.parameters().stream())
                .filter(parameter -> parameter.role() == org.filteredpush.bdq_workbench.model.ParameterRole.PARAMETER)
                .forEach(parameter -> discoveredParameters.putIfAbsent(parameter.source(), parameter));
        return List.copyOf(discoveredParameters.values());
    }

    /**
     * Checks whether a discovered implementation is associated with the given test ID, matching
     * against the implementation's declared "provided version" or "provided test ID", or (as a
     * fallback) a bare UUID extracted from the test ID against the implementation's provided test
     * ID.
     *
     * @param testId the policy test's identifier
     * @param discovered a candidate discovered implementation
     * @return {@code true} if {@code discovered} is associated with {@code testId}
     */
    private static boolean matchesTestIdentifier(String testId, DiscoveredImplementation discovered) {
        String normalizedTestId = normalizeTestIdentifier(testId);
        if (normalizedTestId == null) {
            return false;
        }
        String providedVersion = normalizeTestIdentifier(discovered.providedVersion());
        if (normalizedTestId.equals(providedVersion)) {
            return true;
        }
        String providedTestId = normalizeTestIdentifier(discovered.providedTestId());
        if (normalizedTestId.equals(providedTestId)) {
            return true;
        }
        String providesFallbackKey = toProvidesKey(normalizedTestId);
        return providesFallbackKey != null && providesFallbackKey.equals(providedTestId);
    }

    private static String normalizeTestIdentifier(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * Extracts a bare UUID from a normalized test identifier, for matching against an
     * implementation's "provides" test ID when the identifier is otherwise namespaced/versioned
     * differently.
     *
     * @param normalizedId the normalized test identifier to search
     * @return the first UUID found in {@code normalizedId}, or {@code null} if none is present
     */
    private static String toProvidesKey(String normalizedId) {
        if (normalizedId == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
                .matcher(normalizedId);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Formats a response's status and result for display as {@code "<status> / <result>"},
     * falling back to whichever of the two is non-blank, or {@code null} if both are blank.
     *
     * @param response the response to format
     * @return the formatted status/result text, or {@code null} if the response has neither
     */
    private static String formatStructuredResponse(Response response) {
        String responseStatus = response.responseStatus();
        String responseResult = response.responseResult();
        if ((responseStatus == null || responseStatus.isBlank())
                && (responseResult == null || responseResult.isBlank())) {
            return null;
        }
        if (responseStatus == null || responseStatus.isBlank()) {
            return responseResult;
        }
        return responseResult == null || responseResult.isBlank()
                ? responseStatus
                : responseStatus + " / " + responseResult;
    }

    private static String describeParameterization(org.filteredpush.bdq_workbench.model.ParameterizationCapability capability) {
        return capability == org.filteredpush.bdq_workbench.model.ParameterizationCapability.BOTH
                ? "PARAMETERIZED_VERSION_AVAILABLE"
                : capability.name();
    }

    private static void setParameterFieldState(Map<String, JTextField> parameterFields, boolean enabled) {
        parameterFields.values().forEach(field -> field.setEnabled(enabled));
    }

    /**
     * Handles "Save Parameters...": prompts for a destination file and writes the binding grid's
     * current per-test parameter settings to it as JSON, reporting any I/O failure in a dialog.
     *
     * @param frame owner frame for the save dialog and any error dialog
     * @param bindingGrid the binding review table whose parameter settings are saved
     */
    private static void saveParameterSettings(JFrame frame, JTable bindingGrid) {
        if (!(bindingGrid.getModel() instanceof BindingReviewTableModel reviewModel)) {
            return;
        }
        String path = chooseSaveFile(frame, "Save parameter settings", "bdq-parameter-settings.json");
        if (path == null) {
            return;
        }
        try {
            writeParameterSettings(Path.of(path), reviewModel.parameterSettings());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    frame,
                    "Unable to save parameter settings: " + e.getMessage(),
                    "Save failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Handles "Load Parameters...": prompts for a JSON settings file, applies its per-test
     * parameter settings to the binding grid, rebinds, and refreshes the preflight UI to reflect
     * the loaded settings, reporting any I/O failure in a dialog.
     *
     * @param frame owner frame for the open dialog and any error dialog
     * @param bindingGrid the binding review table the settings are applied to
     * @param state holder for the current {@link PreflightState}
     * @param statusArea status log refreshed after loading settings
     * @param resultSummaryArea result summary area refreshed after loading settings
     * @param startRun re-enabled/relabeled after loading settings
     * @param runWithAvailableOnly whether the run may proceed with unresolved tests
     * @param saveParameters enabled state refreshed after loading settings
     * @param loadParameters enabled state refreshed after loading settings
     * @param monitorHeader the monitor page's title label, reset by {@link #updatePreflightUi}
     */
    private static void loadParameterSettings(
            JFrame frame,
            JTable bindingGrid,
            PreflightState[] state,
            JTextArea statusArea,
            JTextArea resultSummaryArea,
            JButton startRun,
            JCheckBox runWithAvailableOnly,
            JButton saveParameters,
            JButton loadParameters,
            JLabel monitorHeader) {
        if (state[0] == null || !(bindingGrid.getModel() instanceof BindingReviewTableModel reviewModel)) {
            return;
        }
        String path = chooseFile(frame, "Load parameter settings");
        if (path == null) {
            return;
        }
        try {
            Map<String, BindingReviewTableModel.ParameterSettings> settings = readParameterSettings(Path.of(path));
            reviewModel.applyParameterSettings(settings);
            PreparedRun rebound = applyParameterEdits(state[0].preparedRun(), reviewModel);
            updatePreflightUi(
                    state,
                    rebound,
                    bindingGrid,
                    statusArea,
                    resultSummaryArea,
                    startRun,
                    runWithAvailableOnly,
                    saveParameters,
                    loadParameters,
                    monitorHeader);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    frame,
                    "Unable to load parameter settings: " + e.getMessage(),
                    "Load failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Serializes per-test parameter settings to a JSON file.
     *
     * @param path destination file path
     * @param settings per-test parameter settings, keyed by test ID
     * @throws IOException if the file cannot be written
     */
    private static void writeParameterSettings(
            Path path,
            Map<String, BindingReviewTableModel.ParameterSettings> settings) throws IOException {
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), settings);
    }

    /**
     * Deserializes per-test parameter settings from a JSON file previously written by
     * {@link #writeParameterSettings}.
     *
     * @param path source file path
     * @return per-test parameter settings, keyed by test ID
     * @throws IOException if the file cannot be read or does not contain valid JSON
     */
    private static Map<String, BindingReviewTableModel.ParameterSettings> readParameterSettings(Path path) throws IOException {
        return OBJECT_MAPPER.readValue(
                path.toFile(),
                new TypeReference<LinkedHashMap<String, BindingReviewTableModel.ParameterSettings>>() {
                });
    }

    /** A use case combo box entry; displays as {@code "<label> (<id>)"}. */
    private record UseCaseChoice(String id, String label) {
        @Override
        public String toString() {
            return label + " (" + id + ")";
        }
    }

    /** A text field paired with its associated "Browse..." button, as built by {@link #addPickerField}. */
    private record PickerField(JTextField field, JButton button) {
    }

    /** The current preflight result being reviewed in the monitor UI. */
    private record PreflightState(PreparedRun preparedRun) {
        /**
         * @return {@code true} if every policy test resolved from definitions and was
         *     successfully bound to a discovered implementation
         */
        boolean isFullyResolved() {
            return preparedRun.plan().unresolvedTests().isEmpty()
                    && preparedRun.bindingResult().unresolved().isEmpty();
        }
    }
}
