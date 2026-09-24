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
import java.awt.Color;
import java.awt.Dimension;
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
import javax.swing.DefaultCellEditor;
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
import javax.swing.SwingConstants;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import org.filteredpush.bdq_workbench.execution.ExecutionProgressListener;
import org.filteredpush.bdq_workbench.execution.ParallelPhaseExecutionService;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.ingest.DatasetViewIO;
import org.filteredpush.bdq_workbench.ingest.DefaultIngestService;
import org.filteredpush.bdq_workbench.ingest.DatasetSchemaInspector;
import org.filteredpush.bdq_workbench.ingest.DatasetViewSuggester;
import org.filteredpush.bdq_workbench.ingest.RelationalDatasetIngestor;
import org.filteredpush.bdq_workbench.ingest.RelationalIngestResult;
import org.filteredpush.bdq_workbench.model.RecordFilterSummary;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Policy;
import org.filteredpush.bdq_workbench.model.PreparedRun;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordFilterSpec;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.filteredpush.bdq_workbench.rdf_policy.InformationElementIndex;
import org.filteredpush.bdq_workbench.rdf_policy.RdfPolicyResolverService;
import org.filteredpush.bdq_workbench.rdf_policy.UseCaseXmlParser;
import org.filteredpush.bdq_workbench.reporting.DetailedResponseStreamExporter;
import org.filteredpush.bdq_workbench.reporting.RdfResponseExporter;
import org.filteredpush.bdq_workbench.reporting.ReportingService;
import org.filteredpush.bdq_workbench.reporting.SummaryReportExporter;
import org.filteredpush.bdq_workbench.reporting.TestResultsSummaryService;
import org.filteredpush.bdq_workbench.reporting.UnresolvedResponsesExporter;
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
    private static final int RECORD_FILTER_SUGGESTION_LIMIT = 20;
    private static final int FINALIZATION_STAGE_STEP_COUNT = 5;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Frame width that fits the monitor card's full button row on one line. */
    private static final int PREFERRED_FRAME_WIDTH = 1220;

    /** Frame height that leaves the binding review grid usable below the header. */
    private static final int PREFERRED_FRAME_HEIGHT = 760;

    /** Smallest frame the layout stays usable at; button rows wrap rather than clip below this. */
    private static final int MINIMUM_FRAME_WIDTH = 720;

    /** Smallest frame height the layout stays usable at. */
    private static final int MINIMUM_FRAME_HEIGHT = 560;

    /** Fraction of the screen the window may occupy when the screen is smaller than preferred. */
    private static final double MAXIMUM_SCREEN_FRACTION = 0.9;

    private static final String DEFAULT_USECASE_SOURCE = WorkbenchDefaults.USE_CASE_SOURCE;
    private static final String DEFAULT_TEST_DEFINITIONS_SOURCE = WorkbenchDefaults.TEST_DEFINITIONS_SOURCE;
    private static final String DEFAULT_ONTOLOGY_SOURCE = WorkbenchDefaults.ONTOLOGY_SOURCE;

    /** Utility class; not instantiable. */
    private BdqWorkbenchGui() {
    }

    /**
     * Application entry point for the desktop GUI. Loads default {@link AppConfig} values (from
     * system properties/environment, via {@link ConfigLoader}), builds the main window on the
     * Swing event dispatch thread, and shows it. Startup failures are logged and reported to the
     * user in a dialog rather than propagated, since there is no console the user is expected to
     * be watching.
     *
     * <p>{@code overrides} carries any settings the user gave on the command line alongside
     * {@code --gui}, so the window opens with those values in its fields instead of the
     * built-in defaults.
     *
     * @param overrides property-name-keyed values to seed the form with, empty for the defaults
     */
    static void launch(Map<String, String> overrides) {
        LOG.debug("Scheduling BDQ Workbench GUI startup");
        SwingUtilities.invokeLater(() -> {
            try {
                ConfigLoader loader = new ConfigLoader();
                AppConfig defaults = loader.load(overrides);
                createFrame(defaults, overrides).setVisible(true);
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
     * Chooses the window's opening size: large enough for the monitor card's button row to fit
     * on one line, but never larger than the screen it has to open on.
     *
     * @return the size to open the main window at
     */
    private static java.awt.Dimension defaultFrameSize() {
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        int width = Math.min(PREFERRED_FRAME_WIDTH, (int) (screen.width * MAXIMUM_SCREEN_FRACTION));
        int height = Math.min(PREFERRED_FRAME_HEIGHT, (int) (screen.height * MAXIMUM_SCREEN_FRACTION));
        return new java.awt.Dimension(
                Math.max(width, MINIMUM_FRAME_WIDTH), Math.max(height, MINIMUM_FRAME_HEIGHT));
    }

    /**
     * Builds the main application window: the "setup" card (dataset/use case/advanced options
     * form) and "monitor" card (status log, binding review grid, result summary, and progress
     * bar), swapped via a {@link CardLayout}, plus all button/menu action wiring that connects
     * user input to preflight preparation and execution of the workbench run.
     *
     * @param defaults initial field values (dataset path, use case ID, discovery packages,
     *     thread count) loaded before the window is shown
     * @param overrides raw command line values used to seed the resource source fields, whose
     *     contents are source strings rather than the resolved paths {@code defaults} holds
     * @return the fully constructed, not-yet-visible application frame
     */
    private static JFrame createFrame(AppConfig defaults, Map<String, String> overrides) {
        JFrame frame = new JFrame("BDQ Workbench");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new java.awt.Dimension(MINIMUM_FRAME_WIDTH, MINIMUM_FRAME_HEIGHT));
        frame.setSize(defaultFrameSize());

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
        JPanel workflowVisualizationPanel = new JPanel();
        workflowVisualizationPanel.setLayout(new BoxLayout(workflowVisualizationPanel, BoxLayout.Y_AXIS));
        workflowVisualizationPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        resetWorkflowVisualizationPanel(workflowVisualizationPanel);
        JScrollPane resultSummaryScrollPane = new JScrollPane(resultSummaryArea);
        JScrollPane workflowVisualizationScrollPane = new JScrollPane(workflowVisualizationPanel);

        JPanel monitorPanel = new JPanel(new BorderLayout(8, 8));
        JSplitPane bindingGridSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(bindingGrid),
                resultSummaryScrollPane);
        bindingGridSplit.setResizeWeight(0.7d);
        JSplitPane monitorSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(statusArea),
                bindingGridSplit);
        monitorSplit.setResizeWeight(0.25d);
        CardLayout monitorContentCards = new CardLayout();
        JPanel monitorContentPanel = new JPanel(monitorContentCards);
        monitorContentPanel.add(monitorSplit, "results");
        monitorContentPanel.add(workflowVisualizationScrollPane, "workflow");
        monitorPanel.add(monitorContentPanel, BorderLayout.CENTER);
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

        JPanel monitorControls = new JPanel(new WrapLayout(FlowLayout.RIGHT));
        JButton loadParameters = new JButton("Load Parameters...");
        loadParameters.setEnabled(false);
        JButton saveParameters = new JButton("Save Parameters...");
        saveParameters.setEnabled(false);
        JButton toggleWorkflowView = new JButton("Show Workflow Visualization");
        toggleWorkflowView.setEnabled(false);
        JButton backToSetup = new JButton("Back to Select Inputs");
        JButton startRun = new JButton("Start Run");
        startRun.setEnabled(false);
        JButton closeButton = new JButton("Quit");
        monitorControls.add(loadParameters);
        monitorControls.add(saveParameters);
        monitorControls.add(toggleWorkflowView);
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
        PickerField datasetView = addPickerField(form, frame, "Dataset view (JSON)",
                overrides.getOrDefault("bdq.dataset.view", defaults.datasetView()));
        String[] configuredRecordFilters = new String[] {defaults.recordFilter().toPropertyString()};
        JTextArea recordFilterSummary = new JTextArea(4, 40);
        recordFilterSummary.setEditable(false);
        recordFilterSummary.setLineWrap(true);
        recordFilterSummary.setWrapStyleWord(true);
        recordFilterSummary.setBorder(BorderFactory.createEtchedBorder());
        installTextAreaClipboardSupport(recordFilterSummary);
        updateRecordFilterSummary(recordFilterSummary, configuredRecordFilters[0]);
        JPanel recordFilterRow = new JPanel(new BorderLayout(8, 8));
        recordFilterRow.add(new JLabel("Record filters"), BorderLayout.WEST);
        JPanel recordFilterButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton buildRecordFilters = new JButton("Build Record Filters...");
        JButton buildDatasetView = new JButton("Build Dataset View...");
        JButton clearRecordFilters = new JButton("Clear Filters");
        JButton clearDatasetView = new JButton("Clear View");
        clearRecordFilters.setEnabled(!configuredRecordFilters[0].isBlank());
        clearDatasetView.setEnabled(!datasetView.field().getText().isBlank());
        recordFilterButtons.add(buildDatasetView);
        recordFilterButtons.add(clearDatasetView);
        recordFilterButtons.add(buildRecordFilters);
        recordFilterButtons.add(clearRecordFilters);
        recordFilterRow.add(recordFilterButtons, BorderLayout.CENTER);
        recordFilterRow.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        form.add(recordFilterRow);
        form.add(recordFilterSummary);
        JCheckBox dedupEnabled = new JCheckBox("Reduce repeated test calls by distinct input values", defaults.dedupEnabled());
        dedupEnabled.setHorizontalTextPosition(SwingConstants.LEFT);
        JPanel dedupRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        dedupRow.add(dedupEnabled);
        dedupRow.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        form.add(dedupRow);

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

        JTextField useCaseSource = addField(advanced, "Use case file/URL",
                overrides.getOrDefault("bdq.usecase.file", DEFAULT_USECASE_SOURCE));
        JButton loadUseCases = new JButton("Load use cases");
        JButton pickUseCaseFile = new JButton("Pick use case file");
        JPanel useCaseButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        useCaseButtons.add(loadUseCases);
        useCaseButtons.add(pickUseCaseFile);
        advanced.add(useCaseButtons);

        JTextField testDefinitionsSource = addField(advanced, "Test definitions file/URL",
                firstRdfSource(overrides, DEFAULT_TEST_DEFINITIONS_SOURCE));
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
        JTextField ontologySource = addField(advanced, "BDQ FFDQ ontology file/URL",
                secondRdfSource(overrides, DEFAULT_ONTOLOGY_SOURCE));
        JTextField datasetTable = addField(
                advanced,
                "Dataset table (blank = choose automatically)",
                defaults.datasetTable());
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

        JPanel setupControls = new JPanel(new WrapLayout(FlowLayout.RIGHT));
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
        buildRecordFilters.addActionListener(e -> loadRecordFilterDialog(
                frame,
                dataset.field().getText().trim(),
                configuredRecordFilters,
                recordFilterSummary,
                clearRecordFilters,
                buildRecordFilters));
        buildDatasetView.addActionListener(e -> loadDatasetViewDialog(
                frame,
                dataset.field().getText().trim(),
                datasetView.field(),
                datasetTable.getText().trim(),
                availableUseCaseChoices(useCaseChoice),
                selectedUseCaseId(useCaseChoice),
                useCaseSource.getText().trim(),
                testDefinitionsSource.getText().trim(),
                additionalTestDefinitions.getText().trim(),
                ontologySource.getText().trim()));
        clearDatasetView.addActionListener(e -> {
            datasetView.field().setText("");
            clearDatasetView.setEnabled(false);
        });
        datasetView.field().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                clearDatasetView.setEnabled(!datasetView.field().getText().isBlank());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                clearDatasetView.setEnabled(!datasetView.field().getText().isBlank());
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                clearDatasetView.setEnabled(!datasetView.field().getText().isBlank());
            }
        });
        clearRecordFilters.addActionListener(e -> {
            configuredRecordFilters[0] = "";
            updateRecordFilterSummary(recordFilterSummary, configuredRecordFilters[0]);
            clearRecordFilters.setEnabled(false);
        });

        final boolean[] showingWorkflowView = new boolean[] {false};
        Runnable showTextSummary = () -> {
            showWorkflowVisualization(monitorContentCards, monitorContentPanel, toggleWorkflowView, showingWorkflowView, false);
        };
        Runnable showWorkflowSummary = () -> {
            showWorkflowVisualization(monitorContentCards, monitorContentPanel, toggleWorkflowView, showingWorkflowView, true);
        };
        toggleWorkflowView.addActionListener(e -> {
            if (!toggleWorkflowView.isEnabled()) {
                return;
            }
            if (showingWorkflowView[0]) {
                showTextSummary.run();
            } else {
                showWorkflowSummary.run();
            }
        });

        backToSetup.addActionListener(e -> {
            if (!progress.isVisible()) {
                showTextSummary.run();
                toggleWorkflowView.setEnabled(false);
                resetWorkflowVisualizationPanel(workflowVisualizationPanel);
                cards.show(cardPanel, "setup");
            }
        });

        run.addActionListener(e -> {
            run.setEnabled(false);
            cards.show(cardPanel, "monitor");
            showTextSummary.run();
            toggleWorkflowView.setEnabled(false);
            resetWorkflowVisualizationPanel(workflowVisualizationPanel);
            setStatus(statusArea, "Preparing run configuration...\n");
            progress.setVisible(true);
            startRun.setEnabled(false);

            SwingWorker<PreflightState, Void> preflight = new SwingWorker<>() {
                @Override
                protected PreflightState doInBackground() {
                    AppConfig config = buildConfig(
                            dataset.field().getText().trim(),
                            selectedUseCaseId(useCaseChoice),
                            configuredRecordFilters[0],
                            datasetTable.getText().trim(),
                            datasetView.field().getText().trim(),
                            useCaseSource.getText().trim(),
                            testDefinitionsSource.getText().trim(),
                            additionalTestDefinitions.getText().trim(),
                            ontologySource.getText().trim(),
                            discoveryPackages.getText().trim(),
                            threads.getText().trim(),
                            dedupEnabled.isSelected(),
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
                        showTextSummary.run();
                        toggleWorkflowView.setEnabled(false);
                        resetWorkflowVisualizationPanel(workflowVisualizationPanel);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        LOG.error("Preflight mapping failed", cause);
                        setStatus(statusArea, "Failed to prepare run: " + cause.getMessage() + "\n");
                        resultSummaryArea.setText("Run setup failed.\n");
                        showTextSummary.run();
                        toggleWorkflowView.setEnabled(false);
                        resetWorkflowVisualizationPanel(workflowVisualizationPanel);
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
            showTextSummary.run();
            toggleWorkflowView.setEnabled(false);
            final PreparedRun[] executedRun = new PreparedRun[1];
            final int[] exportStageTotalSteps = {FINALIZATION_STAGE_STEP_COUNT};
            final int[] completedExporters = {0};

            SwingWorker<ExecutionSummary, Void> worker = new SwingWorker<>() {
                @Override
                protected ExecutionSummary doInBackground() {
                    LOG.info("Starting BDQ Workbench execution");
                    BindingReviewTableModel reviewModel = (BindingReviewTableModel) bindingGrid.getModel();
                    PreparedRun editedRun = applyParameterEdits(state[0].preparedRun(), reviewModel);
                    executedRun[0] = editedRun;
                    ExecutionProgressTracker tracker = new ExecutionProgressTracker();
                    return runWorkbench(
	editedRun,
	tracker,
	snapshot -> SwingUtilities.invokeLater(() -> {
                                int max = Math.max(1, snapshot.total());
                                progress.setMaximum(max);
                                progress.setValue(snapshot.completed());
                                int currentStageNumber = currentWorkflowStageNumber(editedRun, snapshot.phase(), false, false);
                                progress.setString(String.format(
		"Workflow stage %d/%d • %s %s (%d active threads) queued=%d completed=%d/%d",
		currentStageNumber,
		totalWorkflowStageCount(),
		snapshot.phase(),
		snapshot.running() > 0 ? "running" : "idle",
		snapshot.running(),
		snapshot.queued(),
		snapshot.completed(),
		snapshot.total()));
                                resultSummaryArea.setText(renderStageOverview(editedRun, snapshot.phase(), false, false, false)
		+ "\n"
		+ renderProgressSnapshot(snapshot));
	}),
	(totalExports, completedExports, detail) -> SwingUtilities.invokeLater(() -> {
                                exportStageTotalSteps[0] = Math.max(
		FINALIZATION_STAGE_STEP_COUNT,
		totalExports + FINALIZATION_STAGE_STEP_COUNT);
                                completedExporters[0] = completedExports;
                                updateFinalStageProgress(
		editedRun,
		progress,
		resultSummaryArea,
		completedExports,
		exportStageTotalSteps[0],
		detail,
		true);
	}));
                }

                @Override
                protected void done() {
                    try {
                        ExecutionSummary summary = get();
                        PreparedRun completedRun = executedRun[0] == null ? state[0].preparedRun() : executedRun[0];
                        LOG.info("BDQ Workbench execution complete: {} outcomes", summary.responses().size());
                        int finalizationProgress = completedExporters[0];
                        monitorHeader.setText(monitorHeaderText("Test Results", completedRun));
                        finalizationProgress++;
                        updateFinalStageProgress(
                                completedRun,
                                progress,
                                resultSummaryArea,
                                finalizationProgress,
                                exportStageTotalSteps[0],
                                "Preparing result summary",
                                true);
                        appendStatus(statusArea, "Completed: " + summary.responses().size() + " outcomes\n");
                        finalizationProgress++;
                        updateFinalStageProgress(
                                completedRun,
                                progress,
                                resultSummaryArea,
                                finalizationProgress,
                                exportStageTotalSteps[0],
                                "Updating workflow visualization",
                                true);
                        updateWorkflowVisualizationPanel(workflowVisualizationPanel, completedRun, summary);
                        toggleWorkflowView.setEnabled(true);
                        showTextSummary.run();
                        finalizationProgress++;
                        updateFinalStageProgress(
                                completedRun,
                                progress,
                                resultSummaryArea,
                                finalizationProgress,
                                exportStageTotalSteps[0],
                                "Updating binding review outputs",
                                true);
                        updateBindingGridExecutionOutputs(bindingGrid, summary);
                        finalizationProgress++;
                        updateFinalStageProgress(
                                completedRun,
                                progress,
                                resultSummaryArea,
                                finalizationProgress,
                                exportStageTotalSteps[0],
                                "Writing response log",
                                true);
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
                        finalizationProgress++;
                        progress.setMaximum(Math.max(1, exportStageTotalSteps[0]));
                        progress.setValue(Math.max(finalizationProgress, exportStageTotalSteps[0]));
                        progress.setString(String.format(
		"Workflow stage %d/%d • Export reports complete",
		totalWorkflowStageCount(),
		totalWorkflowStageCount()));
                        resultSummaryArea.setText(renderStageOverview(completedRun, null, false, true, false)
		+ "\n"
		+ renderResultSummary(summary));
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        LOG.error("BDQ Workbench execution failed", cause);
                        appendStatus(statusArea, "Failed: " + cause.getMessage() + "\n");
                        JOptionPane.showMessageDialog(
                                frame,
                                "BDQ Workbench failed: " + cause.getMessage(),
                                "Execution failed",
                                JOptionPane.ERROR_MESSAGE);
                        PreparedRun failedRun = executedRun[0] == null ? state[0].preparedRun() : executedRun[0];
                        resultSummaryArea.setText(renderStageOverview(failedRun, null, false, false, true)
                                + "\nExecution failed: "
                                + cause.getMessage()
                                + "\n");
                        toggleWorkflowView.setEnabled(false);
                        showTextSummary.run();
                        resetWorkflowVisualizationPanel(workflowVisualizationPanel);
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
     * @param exportProgressListener callback invoked as report exporters and finalization steps
     *     advance workflow stage 9
     * @return the summary of the completed execution
     */
    private static ExecutionSummary runWorkbench(
            PreparedRun preparedRun,
            ExecutionProgressTracker tracker,
            java.util.function.Consumer<ExecutionProgressSnapshot> progressConsumer,
            ExportStageProgressListener exportProgressListener) {
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
        }, new ReportingService.ProgressListener() {
            @Override
            public void onExportStarted(int totalExports) {
                exportProgressListener.onExportProgress(totalExports, 0, "Starting report export");
            }

            @Override
            public void onExporterCompleted(String format, int completedExports, int totalExports) {
                exportProgressListener.onExportProgress(
		totalExports,
		completedExports,
		String.format("Wrote %s report (%d/%d)", format, completedExports, totalExports));
            }
        });
        return facade.runPrepared(preparedRun);
    }

    /**
     * Updates the monitor page to show progress through workflow stage 9 ("Export reports") and
     * the final UI bookkeeping immediately afterward.
     *
     * @param preparedRun the run being executed
     * @param progress the run progress bar
     * @param resultSummaryArea the monitor summary area
     * @param completedSteps completed steps within the final workflow stage
     * @param totalSteps total steps within the final workflow stage
     * @param detail human-readable detail about the current export/finalization sub-step
     * @param exportRunning whether the export/finalization stage is still in progress
     */
    private static void updateFinalStageProgress(
	PreparedRun preparedRun,
	JProgressBar progress,
	JTextArea resultSummaryArea,
	int completedSteps,
	int totalSteps,
	String detail,
	boolean exportRunning) {
        progress.setMaximum(Math.max(1, totalSteps));
        progress.setValue(Math.max(0, Math.min(completedSteps, totalSteps)));
        progress.setString(String.format(
		"Workflow stage %d/%d • Export reports %s (%d/%d)",
		totalWorkflowStageCount(),
		totalWorkflowStageCount(),
		detail,
		Math.max(0, Math.min(completedSteps, totalSteps)),
		Math.max(1, totalSteps)));
        resultSummaryArea.setText(renderStageOverview(preparedRun, null, exportRunning, false, false)
		+ "\n"
		+ "Export progress\n"
		+ "Current stage: Export reports (stage "
		+ totalWorkflowStageCount()
		+ "/"
		+ totalWorkflowStageCount()
		+ ")\n"
		+ "Completed sub-steps: "
		+ Math.max(0, Math.min(completedSteps, totalSteps))
		+ "/"
		+ Math.max(1, totalSteps)
		+ "\n"
		+ "Current action: "
		+ detail
		+ "\n");
    }

    /**
     * Listener that bridges background report-export progress back to the GUI.
     */
    @FunctionalInterface
    private interface ExportStageProgressListener {
        /**
         * Reports progress within workflow stage 9 ("Export reports").
         *
         * @param totalExports the number of configured exporters
         * @param completedExports how many exporters have completed so far
         * @param detail detail about the current export step
         */
        void onExportProgress(int totalExports, int completedExports, String detail);
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
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.RIGHT));
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
        boolean runnableInDialog = binding != null && binding.isRunnable() && !BuiltInMeasureSpec.isBuiltIn(binding);
        outputArea.setText(binding == null
                ? "No runnable implementation is currently bound for this test.\n"
                : !binding.isRunnable()
                        ? "This test is mapped for diagnostics only and is not runnable with the current binding status.\n"
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
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.RIGHT));
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
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.RIGHT));
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
        return createFacade(config, progressListener, new ReportingService.ProgressListener() {
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
     * @param reportingProgressListener notified as reports are exported after execution
     * @return a facade ready to prepare and run {@code config}
     */
    private static WorkbenchFacade createFacade(
	AppConfig config,
	ExecutionProgressListener progressListener,
	ReportingService.ProgressListener reportingProgressListener) {
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
                        new UnresolvedResponsesExporter(),
                        new RdfResponseExporter(config.rdfDefinitions())), reportingProgressListener));
    }

    /**
     * Assembles an {@link AppConfig} from the current setup form field values: resolves (and
     * caches locally, via {@code resolver}) the use case, test definitions, and ontology sources,
     * appends any additional test definition sources, and falls back to {@code defaults}'
     * discovery packages when none are specified.
     *
     * @param dataset dataset file path field value
     * @param selectedUseCaseId ID of the use case chosen in the combo box
     * @param recordFilters record-filter field value in {@code field=value1|value2; field2=value}
     *     form
     * @param datasetTable which of the dataset's tables to run against, named by location,
     *     resource name or Darwin Core row type; blank to let the ingestor choose
     * @param datasetView optional dataset-view JSON file path
     * @param useCaseSource use case RDF file/URL field value
     * @param testDefinitionsSource primary test definitions file/URL field value
     * @param additionalTestDefinitions comma-separated extra test definition files/URLs
     * @param ontologySource BDQ FFDQ ontology file/URL field value
     * @param discoveryPackages comma-separated implementation discovery packages
     * @param threads thread count field value
     * @param dedupEnabled whether distinct-value execution is enabled for this run
     * @param resolver resolves and caches remote/local resource paths
     * @param defaults fallback values used when a field is blank
     * @return the assembled configuration, ready for {@link WorkbenchFacade#prepare(AppConfig)}
     */
    private static AppConfig buildConfig(
            String dataset,
            String selectedUseCaseId,
            String recordFilters,
            String datasetTable,
            String datasetView,
            String useCaseSource,
            String testDefinitionsSource,
            String additionalTestDefinitions,
            String ontologySource,
            String discoveryPackages,
            String threads,
            boolean dedupEnabled,
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
                dedupEnabled,
                RecordFilterSpec.parse(recordFilters),
                datasetTable,
                datasetView);
    }

	/**
	 * Backward-compatible build-config helper used by reflection-based tests.
	 *
	 * <p>Delegates to the dataset-view-aware overload with an empty dataset view path.
	 *
	 * @param dataset dataset file path field value
	 * @param selectedUseCaseId ID of the use case chosen in the combo box
	 * @param recordFilters record-filter field value
	 * @param datasetTable selected dataset table name
	 * @param useCaseSource use case RDF file/URL field value
	 * @param testDefinitionsSource primary test definitions file/URL field value
	 * @param additionalTestDefinitions comma-separated extra test definition files/URLs
	 * @param ontologySource BDQ FFDQ ontology file/URL field value
	 * @param discoveryPackages comma-separated implementation discovery packages
	 * @param threads thread count field value
	 * @param dedupEnabled whether distinct-value execution is enabled for this run
	 * @param resolver resolves and caches remote/local resource paths
	 * @param defaults fallback values used when a field is blank
	 * @return the assembled configuration
	 */
	private static AppConfig buildConfig(
			String dataset,
			String selectedUseCaseId,
			String recordFilters,
			String datasetTable,
			String useCaseSource,
			String testDefinitionsSource,
			String additionalTestDefinitions,
			String ontologySource,
			String discoveryPackages,
			String threads,
			boolean dedupEnabled,
			CachedResourceResolver resolver,
			AppConfig defaults) {
		return buildConfig(
				dataset,
				selectedUseCaseId,
				recordFilters,
				datasetTable,
				"",
				useCaseSource,
				testDefinitionsSource,
				additionalTestDefinitions,
				ontologySource,
				discoveryPackages,
				threads,
				dedupEnabled,
				resolver,
				defaults);
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
        int runnable = state.preparedRun().bindingResult().runnableBindings().size();
        int policyTotal = policyResolved + policyUnresolved;
        RecordFilterSummary filterSummary = state.preparedRun().filterSummary();

        StringBuilder sb = new StringBuilder();
        sb.append("Use case preflight mapping\n");
        appendRecordFilterSummary(sb, filterSummary);
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

        if (!state.preparedRun().bindingResult().runnableBindings().isEmpty()) {
            sb.append("Runnable library mappings:\n");
            state.preparedRun().bindingResult().runnableBindings().forEach(b -> sb.append(" - ")
                    .append(formatTestIdWithLabel(b.testId(), labelsByTestId.get(b.testId())))
                    .append(" -> ")
                    .append(b.fullImplementationSignature())
                    .append(" [")
                    .append(b.bindingStatus())
                    .append(", ")
                    .append(b.methodSelection())
                    .append("]")
                    .append('\n'));
        }
        List<ImplementationBinding> diagnosticOnly = state.preparedRun().bindingResult().bindings().stream()
                .filter(binding -> !binding.isRunnable())
                .toList();
        if (!diagnosticOnly.isEmpty()) {
            sb.append("Mapped but not runnable (retained for diagnostics/unresolved reporting):\n");
            diagnosticOnly.forEach(b -> sb.append(" - ")
                    .append(formatTestIdWithLabel(b.testId(), labelsByTestId.get(b.testId())))
                    .append(" -> ")
                    .append(b.fullImplementationSignature())
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
        if (Files.exists(WorkbenchFacade.bindingDiagnosticsPath())) {
            sb.append("Binding diagnostics file: ")
                    .append(WorkbenchFacade.OUTPUT_DIRECTORY)
                    .append('/')
                    .append(WorkbenchFacade.BINDING_DIAGNOSTICS_FILE)
                    .append('\n');
        }

        sb.append("Hint: right-click a single test row in the table to inspect it or run that test in isolation.\n");
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
     * {@code combo} with one entry per use case, selects {@code defaultUseCaseId} if present,
     * otherwise prefers the "Spatial-Temporal Patterns" use case when available (falling back to
     * the first entry), and reports the outcome in {@code loadStatus}.
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
            String preferredUseCaseId = preferredDefaultUseCaseId(useCases, defaultUseCaseId);
            UseCaseChoice defaultChoice = null;
            for (UseCase useCase : useCases) {
                UseCaseChoice option = new UseCaseChoice(useCase.id(), useCase.label());
                combo.addItem(option);
                if (defaultChoice == null || useCase.id().equals(preferredUseCaseId)) {
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
     * Chooses which use case should be preselected in the setup combo box.
     *
     * <p>An explicit configured default wins when present. Otherwise, if the well-known
     * "Spatial-Temporal Patterns" use case is available, it becomes the default selection.
     *
     * @param useCases the loaded use cases
     * @param configuredDefaultUseCaseId explicitly configured default use case ID, if any
     * @return the preferred default use case ID, or {@code ""} if no use cases are available
     */
    private static String preferredDefaultUseCaseId(List<UseCase> useCases, String configuredDefaultUseCaseId) {
        return WorkbenchDefaults.preferredUseCaseId(useCases, configuredDefaultUseCaseId);
    }

    /**
     * Returns the first comma-separated RDF source a command line run supplied, for the primary
     * test definitions field.
     *
     * @param overrides raw command line values
     * @param fallback the value to use when no RDF sources were supplied
     * @return the source to show in the test definitions field
     */
    private static String firstRdfSource(Map<String, String> overrides, String fallback) {
        List<String> sources = splitCsv(overrides.getOrDefault("bdq.rdf.files", ""));
        return sources.isEmpty() ? fallback : sources.get(0);
    }

    /**
     * Returns the second comma-separated RDF source a command line run supplied, for the
     * ontology field.
     *
     * @param overrides raw command line values
     * @param fallback the value to use when fewer than two RDF sources were supplied
     * @return the source to show in the ontology field
     */
    private static String secondRdfSource(Map<String, String> overrides, String fallback) {
        List<String> sources = splitCsv(overrides.getOrDefault("bdq.rdf.files", ""));
        return sources.size() < 2 ? fallback : sources.get(1);
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

    /**
     * Loads the selected dataset in a background worker, profiles its available record-filter terms,
     * and opens the interactive filter builder dialog.
     *
     * @param frame owner frame for dialogs
     * @param datasetPath selected dataset path
     * @param configuredRecordFilters single-element holder for the serialized filter string
     * @param recordFilterSummary read-only setup summary updated after Apply
     * @param clearRecordFilters clear button enabled state to refresh after Apply
     * @param buildRecordFilters build button temporarily disabled while profiling
     */
    private static void loadRecordFilterDialog(
	JFrame frame,
	String datasetPath,
	String[] configuredRecordFilters,
	JTextArea recordFilterSummary,
	JButton clearRecordFilters,
	JButton buildRecordFilters) {
        if (datasetPath == null || datasetPath.isBlank()) {
	JOptionPane.showMessageDialog(
	frame,
	"Select a dataset before building record filters.",
	"Dataset required",
	JOptionPane.WARNING_MESSAGE);
	return;
        }
        buildRecordFilters.setEnabled(false);
        SwingWorker<RecordFilterDatasetProfile, Void> worker = new SwingWorker<>() {
	@Override
	protected RecordFilterDatasetProfile doInBackground() {
                Path path = Path.of(datasetPath);
                if (!Files.exists(path)) {
	throw new AppException("Dataset input not found: " + datasetPath);
                }
                return profileRecordFilters(new DefaultIngestService().ingest(path));
	}

	@Override
	protected void done() {
                try {
	openRecordFilterDialog(
			frame,
			get(),
			configuredRecordFilters,
			recordFilterSummary,
			clearRecordFilters);
                } catch (Exception ex) {
	Throwable cause = ex.getCause() == null ? ex : ex.getCause();
	LOG.error("Unable to build record filters", cause);
	JOptionPane.showMessageDialog(
			frame,
			"Unable to inspect dataset for record filters: " + cause.getMessage(),
			"Record filter setup failed",
			JOptionPane.ERROR_MESSAGE);
                } finally {
	buildRecordFilters.setEnabled(true);
                }
	}
        };
        worker.execute();
    }

	/**
	 * Loads relational schema metadata and helps the user create/save a dataset view file.
	 *
	 * @param frame owner frame
	 * @param datasetPath selected dataset path
	 * @param datasetViewField dataset-view path field to update
	 * @param datasetTable selected dataset table, if any
	 * @param availableUseCases use cases currently loaded in the setup combo box
	 * @param selectedUseCaseId selected use-case identifier, if any
	 * @param useCaseSource configured use-case RDF source
	 * @param testDefinitionsSource configured primary test-definition source
	 * @param additionalTestDefinitions configured extra test-definition sources
	 * @param ontologySource configured ontology source
	 */
	private static void loadDatasetViewDialog(
			JFrame frame,
			String datasetPath,
			JTextField datasetViewField,
			String datasetTable,
			List<UseCaseChoice> availableUseCases,
			String selectedUseCaseId,
			String useCaseSource,
			String testDefinitionsSource,
			String additionalTestDefinitions,
			String ontologySource) {
		if (datasetPath == null || datasetPath.isBlank()) {
			JOptionPane.showMessageDialog(
					frame,
					"Select a dataset before building a dataset view.",
					"Dataset required",
					JOptionPane.WARNING_MESSAGE);
			return;
		}
		Path path = Path.of(datasetPath);
		if (!Files.exists(path)) {
			JOptionPane.showMessageDialog(
					frame,
					"Dataset input not found: " + datasetPath,
					"Dataset required",
					JOptionPane.ERROR_MESSAGE);
			return;
		}
		DatasetSchemaInspector.DatasetSchemaOverview overview = new DatasetSchemaInspector().inspect(path);
		if (overview.tables().size() <= 1) {
			JOptionPane.showMessageDialog(
					frame,
					overview.describeTables() + ".\nBuild Dataset View is only needed when the dataset has related tables.",
					"Dataset view not needed",
					JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		SwingWorker<DatasetViewPreview, Void> worker = new SwingWorker<>() {
			@Override
			protected DatasetViewPreview doInBackground() {
				RelationalDatasetIngestor ingestor = new RelationalDatasetIngestor();
				RelationalIngestResult relational = ingestor.ingest(path, datasetTable);
				DatasetSchema schema = relational.schema();
				List<String> selectedTerms = requestedDatasetViewTerms(
						selectedUseCaseId == null || selectedUseCaseId.isBlank() ? List.of() : List.of(selectedUseCaseId),
						useCaseSource,
						testDefinitionsSource,
						additionalTestDefinitions,
						ontologySource);
				List<String> allTerms = requestedDatasetViewTerms(
						availableUseCases.stream().map(UseCaseChoice::id).toList(),
						useCaseSource,
						testDefinitionsSource,
						additionalTestDefinitions,
						ontologySource);
				DatasetViewSuggester suggester = new DatasetViewSuggester();
				String initialGrain = schema.tables().stream()
						.map(org.filteredpush.bdq_workbench.model.TableSchema::name)
						.anyMatch(name -> name.equalsIgnoreCase(datasetTable))
								? datasetTable
								: suggester.defaultGrainTable(schema);
				DatasetView suggested = suggester.suggest(schema, initialGrain, selectedTerms);
				return new DatasetViewPreview(
						relational,
						schema,
						suggested,
						availableUseCases,
						selectedUseCaseId == null ? "" : selectedUseCaseId,
						selectedTerms,
						allTerms);
			}

			@Override
			protected void done() {
				try {
					openDatasetViewDialog(frame, datasetViewField, get());
				} catch (Exception e) {
					Throwable cause = e.getCause() == null ? e : e.getCause();
					JOptionPane.showMessageDialog(
							frame,
							"Unable to inspect dataset for dataset views: " + cause.getMessage(),
							"Dataset view setup failed",
							JOptionPane.ERROR_MESSAGE);
				}
			}
		};
		worker.execute();
	}

	/**
	 * Opens the dataset-view builder dialog for one already-profiled relational dataset.
	 *
	 * @param frame owner frame
	 * @param datasetViewField dataset-view path field to update when the user loads or saves a view
	 * @param previewData prepared relational schema, use-case term scope, and starter suggestion
	 */
	private static void openDatasetViewDialog(JFrame frame, JTextField datasetViewField, DatasetViewPreview previewData) {
		DatasetViewIO io = new DatasetViewIO();
		DatasetViewSuggester suggester = new DatasetViewSuggester();
		RelationalIngestResult relational = previewData.relational();
		DatasetSchema schema = previewData.schema();
		JTextArea schemaDetails = readOnlyTextArea(renderDatasetViewSchemaText(schema));
		DatasetView[] currentView = {previewData.suggested()};
		List<DatasetViewUseCaseScope> scopes = datasetViewScopes(previewData);
		JComboBox<DatasetViewUseCaseScope> scopeChoice = new JComboBox<>(scopes.toArray(DatasetViewUseCaseScope[]::new));
		JComboBox<String> grainChoice = new JComboBox<>(schema.tables().stream()
				.map(org.filteredpush.bdq_workbench.model.TableSchema::name)
				.toArray(String[]::new));
		grainChoice.setSelectedItem(previewData.suggested().grainTable());
		JTextArea requestedTermsDetails = readOnlyTextArea("");
		DefaultTableModel joinsModel = new DefaultTableModel(
				new Object[] {"Include", "Source table", "Join path", "Multiplicity"}, 0) {
			@Override
			public Class<?> getColumnClass(int columnIndex) {
				return columnIndex == 0
						? Boolean.class
						: columnIndex == 3 ? DatasetViewCardinalityPolicy.class : String.class;
			}

			@Override
			public boolean isCellEditable(int row, int column) {
				return column == 0 || column == 3;
			}
		};
		JTable joinsTable = new JTable(joinsModel);
		joinsTable.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(
				new JComboBox<>(DatasetViewCardinalityPolicy.values())));
		DefaultTableModel mappingsModel = new DefaultTableModel(
				new Object[] {"Darwin Core term", "Source table", "Source column"}, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return true;
			}
		};
		JTable mappingsTable = new JTable(mappingsModel);
		mappingsTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(
				new JComboBox<>(schema.tables().stream()
						.map(org.filteredpush.bdq_workbench.model.TableSchema::name)
						.toArray(String[]::new))));
		DefaultTableModel previewTableModel = new DefaultTableModel();
		JTable previewTable = new JTable(previewTableModel);
		previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		JTextArea previewDiagnostics = readOnlyTextArea("");
		JButton autoMap = new JButton("Auto Map");
		JButton addMapping = new JButton("Add Mapping");
		JButton removeMapping = new JButton("Remove Mapping");
		JButton refreshPreview = new JButton("Refresh Preview");
		JButton save = new JButton("Save View...");
		JButton load = new JButton("Load View...");
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
		controls.add(new JLabel("Requested terms"));
		controls.add(scopeChoice);
		controls.add(new JLabel("Grain table"));
		controls.add(grainChoice);
		controls.add(autoMap);
		controls.add(addMapping);
		controls.add(removeMapping);
		controls.add(refreshPreview);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(load);
		buttons.add(save);
		JPanel panel = new JPanel(new BorderLayout(8, 8));
		JPanel mappingPanel = new JPanel(new BorderLayout(6, 6));
		mappingPanel.add(new JScrollPane(requestedTermsDetails), BorderLayout.NORTH);
		JSplitPane mappingSplit = new JSplitPane(
				JSplitPane.VERTICAL_SPLIT,
				new JScrollPane(joinsTable),
				new JScrollPane(mappingsTable));
		mappingSplit.setResizeWeight(0.35d);
		mappingPanel.add(mappingSplit, BorderLayout.CENTER);
		JSplitPane upper = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(schemaDetails), mappingPanel);
		upper.setResizeWeight(0.35d);
		JSplitPane previewSplit = new JSplitPane(
				JSplitPane.VERTICAL_SPLIT,
				new JScrollPane(previewTable),
				new JScrollPane(previewDiagnostics));
		previewSplit.setResizeWeight(0.75d);
		JSplitPane layout = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, previewSplit);
		layout.setResizeWeight(0.58d);
		panel.add(controls, BorderLayout.NORTH);
		panel.add(layout, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);
		JDialog dialog = new JDialog(frame, "Build Dataset View", true);
		dialog.setContentPane(panel);
		dialog.setSize(1200, 760);
		dialog.setLocationRelativeTo(frame);

		Runnable applySuggestedDraft = () -> {
			DatasetViewUseCaseScope scope = (DatasetViewUseCaseScope) scopeChoice.getSelectedItem();
			String grain = String.valueOf(grainChoice.getSelectedItem());
			List<String> requestedTerms = scope == null ? List.of() : scope.requestedTerms();
			DatasetView suggested = suggester.suggest(schema, grain, requestedTerms);
			populateDatasetViewJoinRows(joinsModel, suggester, schema, grain, suggested);
			populateDatasetViewMappingRows(mappingsModel, suggested, requestedTerms);
			requestedTermsDetails.setText(renderDatasetViewRequestedTermsText(
					scope == null ? "Selected use case" : scope.label(),
					scope == null ? List.of() : scope.applicableUseCases(),
					requestedTerms));
			currentView[0] = suggested;
			updateDatasetViewPreview(relational, previewTableModel, previewDiagnostics, currentView[0]);
		};
		Runnable refreshFromCurrentDraft = () -> {
			currentView[0] = buildDatasetViewFromTables(
					schema,
					String.valueOf(grainChoice.getSelectedItem()),
					joinsModel,
					mappingsModel);
			updateDatasetViewPreview(relational, previewTableModel, previewDiagnostics, currentView[0]);
		};
		scopeChoice.addActionListener(e -> applySuggestedDraft.run());
		grainChoice.addActionListener(e -> applySuggestedDraft.run());
		autoMap.addActionListener(e -> applySuggestedDraft.run());
		addMapping.addActionListener(e -> mappingsModel.addRow(new Object[] {"", "", ""}));
		removeMapping.addActionListener(e -> {
			int row = mappingsTable.getSelectedRow();
			if (row >= 0) {
				mappingsModel.removeRow(row);
				refreshFromCurrentDraft.run();
			}
		});
		refreshPreview.addActionListener(e -> refreshFromCurrentDraft.run());

		load.addActionListener(e -> {
			String selected = chooseFile(frame, "Select dataset view JSON");
			if (selected == null) {
				return;
			}
			try {
				DatasetView view = io.load(Path.of(selected));
				io.validateCompatibility(view, schema);
				grainChoice.setSelectedItem(view.grainTable());
				populateDatasetViewJoinRows(joinsModel, suggester, schema, view.grainTable(), view);
				populateDatasetViewMappingRows(
					mappingsModel,
					view,
					((DatasetViewUseCaseScope) scopeChoice.getSelectedItem()).requestedTerms());
				currentView[0] = view;
				updateDatasetViewPreview(relational, previewTableModel, previewDiagnostics, currentView[0]);
			} catch (AppException ex) {
				JOptionPane.showMessageDialog(
						frame,
						"Unable to load dataset view: " + ex.getMessage(),
						"Dataset view load failed",
						JOptionPane.ERROR_MESSAGE);
			}
		});
		save.addActionListener(e -> {
			String selected = chooseSaveFile(frame, "Save dataset view JSON", "bdq-dataset-view.json");
			if (selected == null) {
				return;
			}
			refreshFromCurrentDraft.run();
			io.save(Path.of(selected), currentView[0]);
			datasetViewField.setText(selected);
			dialog.dispose();
		});
		applySuggestedDraft.run();
		dialog.setVisible(true);
	}

	/**
	 * Resolves the information-element terms the chosen use cases' tests actually reference.
	 *
	 * @param selectedUseCaseIds use-case identifiers whose information elements should be merged
	 * @param useCaseSource configured use-case RDF source
	 * @param testDefinitionsSource configured primary test-definition source
	 * @param additionalTestDefinitions configured extra test-definition sources
	 * @param ontologySource configured ontology source
	 * @return requested Darwin Core terms for the chosen use cases, or starter terms when
	 *     resolution fails
	 */
	private static List<String> requestedDatasetViewTerms(
			List<String> selectedUseCaseIds,
			String useCaseSource,
			String testDefinitionsSource,
			String additionalTestDefinitions,
			String ontologySource) {
		if (selectedUseCaseIds.isEmpty()) {
			return List.of();
		}
		try {
			CachedResourceResolver resolver = new CachedResourceResolver();
			Path useCaseXml = resolver.resolve(useCaseSource, cacheNameFor(useCaseSource));
			List<Path> rdfSources = new ArrayList<>();
			rdfSources.add(resolver.resolve(testDefinitionsSource, cacheNameFor(testDefinitionsSource)));
			rdfSources.add(resolver.resolve(ontologySource, cacheNameFor(ontologySource)));
			for (String extra : splitCsv(additionalTestDefinitions)) {
				rdfSources.add(resolver.resolve(extra, cacheNameFor(extra)));
			}
			RdfPolicyResolverService resolverService = new RdfPolicyResolverService(useCaseXml, rdfSources);
			InformationElementIndex index = new InformationElementIndex(rdfSources);
			java.util.Set<String> terms = new java.util.LinkedHashSet<>();
			for (String selectedUseCaseId : selectedUseCaseIds) {
				try {
					ExecutionPlan plan = resolverService.resolve(selectedUseCaseId);
					terms.addAll(index.termsFor(plan.tests()));
				} catch (RuntimeException e) {
					LOG.warn("Unable to resolve use case {} while building dataset-view suggestions", selectedUseCaseId, e);
				}
			}
			return terms.isEmpty()
					? List.of("occurrenceID", "scientificName", "eventDate", "decimalLatitude", "decimalLongitude")
					: List.copyOf(terms);
		} catch (RuntimeException e) {
			LOG.warn("Unable to resolve use-case information elements for dataset-view suggestion", e);
			return List.of("occurrenceID", "scientificName", "eventDate", "decimalLatitude", "decimalLongitude");
		}
	}

	/**
	 * Renders the dataset schema pane of the dataset-view builder.
	 *
	 * @param schema the discovered dataset schema
	 * @return the rendered schema description
	 */
	private static String renderDatasetViewSchemaText(DatasetSchema schema) {
		StringBuilder builder = new StringBuilder();
		builder.append("Schema fingerprint: ").append(schema.schemaFingerprint()).append('\n');
		builder.append("Tables and columns:\n");
		schema.tables().forEach(table -> {
			builder.append(" - ")
					.append(table.name())
					.append(" [")
					.append(table.rowType())
					.append("]\n");
			table.columns().forEach(column -> builder.append("    • ").append(column).append('\n'));
		});
		builder.append("Relationships:\n");
		if (schema.relationships().isEmpty()) {
			builder.append(" - none\n");
		} else {
			schema.relationships().forEach(relationship -> builder.append(" - ")
					.append(relationship.fromTable())
					.append('.')
					.append(relationship.fromColumn())
					.append(" -> ")
					.append(relationship.toTable())
					.append('.')
					.append(relationship.toColumn())
					.append('\n'));
		}
		return builder.toString();
	}

	/**
	 * Renders the requested-term and use-case summary for the editable dataset-view builder.
	 *
	 * @param scopeLabel human-readable requested-term scope label
	 * @param applicableUseCases use cases whose information elements are being shown
	 * @param requestedTerms requested Darwin Core terms for the current scope
	 * @return rendered requested-term summary
	 */
	private static String renderDatasetViewRequestedTermsText(
			String scopeLabel,
			List<UseCaseChoice> applicableUseCases,
			List<String> requestedTerms) {
		StringBuilder builder = new StringBuilder();
		builder.append("Requested terms source: ").append(scopeLabel).append('\n');
		builder.append("Applicable use cases:\n");
		if (applicableUseCases.isEmpty()) {
			builder.append(" - none resolved; using starter occurrence terms\n");
		} else {
			applicableUseCases.forEach(choice -> builder.append(" - ")
					.append(choice.label())
					.append(" (")
					.append(choice.id())
					.append(")\n"));
		}
		builder.append("Information elements to map:\n");
		if (requestedTerms.isEmpty()) {
			builder.append(" - occurrenceID\n")
					.append(" - scientificName\n")
					.append(" - eventDate\n")
					.append(" - decimalLatitude\n")
					.append(" - decimalLongitude\n");
		} else {
			requestedTerms.forEach(term -> builder.append(" - ").append(term).append('\n'));
		}
		return builder.toString();
	}

	/**
	 * Populates the join table from the schema and the current draft view.
	 *
	 * @param joinsModel editable join-table model
	 * @param suggester schema-based dataset-view suggester
	 * @param schema discovered schema
	 * @param grainTable currently selected grain table
	 * @param view current draft view
	 */
	private static void populateDatasetViewJoinRows(
			DefaultTableModel joinsModel,
			DatasetViewSuggester suggester,
			DatasetSchema schema,
			String grainTable,
			DatasetView view) {
		joinsModel.setRowCount(0);
		Map<String, DatasetViewJoin> configured = new LinkedHashMap<>();
		view.joins().forEach(join -> configured.put(join.sourceTable().toLowerCase(), join));
		for (DatasetViewSuggester.JoinCandidate candidate : suggester.joinCandidates(schema, grainTable)) {
			DatasetViewJoin configuredJoin = configured.get(candidate.sourceTable().toLowerCase());
			joinsModel.addRow(new Object[] {
					configuredJoin != null,
					candidate.sourceTable(),
					candidate.sourceTable() + "." + candidate.sourceColumn()
							+ " -> " + candidate.targetTable() + "." + candidate.targetColumn(),
					configuredJoin == null ? DatasetViewCardinalityPolicy.REJECT : configuredJoin.cardinalityPolicy()});
		}
	}

	/**
	 * Populates the mapping table from the current draft view and the requested-term set.
	 *
	 * @param mappingsModel editable mapping-table model
	 * @param view current draft view
	 * @param requestedTerms requested Darwin Core terms for the current scope
	 */
	private static void populateDatasetViewMappingRows(
			DefaultTableModel mappingsModel,
			DatasetView view,
			List<String> requestedTerms) {
		mappingsModel.setRowCount(0);
		Map<String, DatasetViewMapping> configured = new LinkedHashMap<>();
		view.mappings().forEach(mapping -> configured.put(mapping.term(), mapping));
		java.util.Set<String> terms = new java.util.LinkedHashSet<>(requestedTerms);
		terms.addAll(configured.keySet());
		for (String term : terms) {
			DatasetViewMapping mapping = configured.get(term);
			mappingsModel.addRow(new Object[] {
					term,
					mapping == null ? "" : mapping.sourceTable(),
					mapping == null ? "" : mapping.sourceColumn()});
		}
	}

	/**
	 * Builds a dataset view from the currently edited join and mapping tables.
	 *
	 * @param schema discovered schema
	 * @param grainTable selected grain table
	 * @param joinsModel editable join-table model
	 * @param mappingsModel editable mapping-table model
	 * @return the current dataset-view draft
	 */
	private static DatasetView buildDatasetViewFromTables(
			DatasetSchema schema,
			String grainTable,
			DefaultTableModel joinsModel,
			DefaultTableModel mappingsModel) {
		List<DatasetViewJoin> joins = new ArrayList<>();
		for (int row = 0; row < joinsModel.getRowCount(); row++) {
			if (!Boolean.TRUE.equals(joinsModel.getValueAt(row, 0))) {
				continue;
			}
			String sourceTable = String.valueOf(joinsModel.getValueAt(row, 1)).trim();
			Object policy = joinsModel.getValueAt(row, 3);
			if (sourceTable.isBlank() || !(policy instanceof DatasetViewCardinalityPolicy cardinalityPolicy)) {
				continue;
			}
			joins.add(new DatasetViewJoin(sourceTable, sourceTable, cardinalityPolicy));
		}
		List<DatasetViewMapping> mappings = new ArrayList<>();
		for (int row = 0; row < mappingsModel.getRowCount(); row++) {
			String term = String.valueOf(mappingsModel.getValueAt(row, 0)).trim();
			String sourceTable = String.valueOf(mappingsModel.getValueAt(row, 1)).trim();
			String sourceColumn = String.valueOf(mappingsModel.getValueAt(row, 2)).trim();
			if (term.isBlank() || sourceTable.isBlank() || sourceColumn.isBlank()) {
				continue;
			}
			mappings.add(new DatasetViewMapping(term, sourceTable, sourceColumn));
		}
		return new DatasetView(grainTable, schema.schemaFingerprint(), joins, mappings);
	}

	/**
	 * Updates the preview table and diagnostics for the current dataset-view draft.
	 *
	 * @param relational relational ingest result used for previewing
	 * @param previewTableModel preview table model to refresh
	 * @param diagnosticsArea diagnostics area to refresh
	 * @param view current dataset-view draft
	 */
	private static void updateDatasetViewPreview(
			RelationalIngestResult relational,
			DefaultTableModel previewTableModel,
			JTextArea diagnosticsArea,
			DatasetView view) {
		org.filteredpush.bdq_workbench.ingest.ViewFlattenResult preview =
				new org.filteredpush.bdq_workbench.ingest.ViewFlattener().flatten(relational, view);
		java.util.Set<String> columns = new java.util.LinkedHashSet<>();
		columns.add("recordId");
		preview.dataset().records().stream().limit(5).forEach(row -> columns.addAll(row.terms().keySet()));
		previewTableModel.setColumnIdentifiers(columns.toArray());
		previewTableModel.setRowCount(0);
		preview.dataset().records().stream().limit(5).forEach(row -> {
			List<Object> values = new ArrayList<>();
			values.add(row.id());
			columns.stream().skip(1).forEach(column -> values.add(row.terms().getOrDefault(column, "")));
			previewTableModel.addRow(values.toArray());
		});
		diagnosticsArea.setText(renderDatasetViewPreviewDiagnosticsText(preview));
	}

	/**
	 * Renders preview diagnostics beneath the preview table.
	 *
	 * @param preview current flattening preview
	 * @return rendered diagnostics
	 */
	private static String renderDatasetViewPreviewDiagnosticsText(
			org.filteredpush.bdq_workbench.ingest.ViewFlattenResult preview) {
		StringBuilder builder = new StringBuilder();
		builder.append("Preview rows shown: ").append(Math.min(5, preview.dataset().records().size())).append('\n');
		if (!preview.diagnostics().isEmpty()) {
			builder.append("Warnings and diagnostics:\n");
			preview.diagnostics().forEach(message -> builder.append(" - ").append(message).append('\n'));
		} else {
			builder.append("No preview warnings.");
		}
		return builder.toString();
	}

	/**
	 * Builds the available requested-term scopes for the dataset-view builder.
	 *
	 * @param previewData prepared dataset-view preview payload
	 * @return available requested-term scope choices
	 */
	private static List<DatasetViewUseCaseScope> datasetViewScopes(DatasetViewPreview previewData) {
		List<DatasetViewUseCaseScope> scopes = new ArrayList<>();
		List<UseCaseChoice> selected = previewData.availableUseCases().stream()
				.filter(choice -> choice.id().equals(previewData.selectedUseCaseId()))
				.toList();
		scopes.add(new DatasetViewUseCaseScope("Selected use case", previewData.selectedTerms(), selected));
		if (previewData.availableUseCases().size() > 1) {
			scopes.add(new DatasetViewUseCaseScope("All loaded use cases", previewData.allTerms(),
					previewData.availableUseCases()));
		}
		return scopes;
	}

	/**
	 * Returns the currently loaded use cases from the GUI combo box.
	 *
	 * @param combo use-case combo box
	 * @return currently loaded use cases
	 */
	private static List<UseCaseChoice> availableUseCaseChoices(JComboBox<UseCaseChoice> combo) {
		List<UseCaseChoice> choices = new ArrayList<>();
		for (int index = 0; index < combo.getItemCount(); index++) {
			UseCaseChoice choice = combo.getItemAt(index);
			if (choice != null) {
				choices.add(choice);
			}
		}
		return choices;
	}

	/**
	 * Creates a standard read-only text area for dataset-view builder panes.
	 *
	 * @param text the text to display
	 * @return configured read-only text area
	 */
	private static JTextArea readOnlyTextArea(String text) {
		JTextArea textArea = new JTextArea(text, 18, 40);
		textArea.setEditable(false);
		textArea.setLineWrap(true);
		textArea.setWrapStyleWord(true);
		installTextAreaClipboardSupport(textArea);
		return textArea;
	}

    /**
     * Opens the interactive record-filter dialog for one already-profiled dataset.
     *
     * @param frame owner frame for the dialog
     * @param profile profiled dataset terms and value suggestions
     * @param configuredRecordFilters single-element holder for the serialized filter string
     * @param recordFilterSummary read-only setup summary updated after Apply
     * @param clearRecordFilters clear button enabled state to refresh after Apply
     */
    private static void openRecordFilterDialog(
	JFrame frame,
	RecordFilterDatasetProfile profile,
	String[] configuredRecordFilters,
	JTextArea recordFilterSummary,
	JButton clearRecordFilters) {
        JDialog dialog = new JDialog(frame, "Record Filters", true);
        dialog.setLayout(new BorderLayout(8, 8));

        JPanel rowsPanel = new JPanel();
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        List<RecordFilterRowWidgets> rows = new ArrayList<>();

        RecordFilterSpec existing = RecordFilterSpec.parse(configuredRecordFilters[0]);
        if (existing.criteria().isEmpty()) {
	rows.add(addRecordFilterRow(rowsPanel, profile, null, ""));
        } else {
	existing.criteria().forEach((field, values) ->
	rows.add(addRecordFilterRow(rowsPanel, profile, field, String.join(" | ", values))));
        }

        JButton addRow = new JButton("Add Filter");
        addRow.addActionListener(e -> {
	rows.add(addRecordFilterRow(rowsPanel, profile, null, ""));
	rowsPanel.revalidate();
	rowsPanel.repaint();
        });

        JButton apply = new JButton("Apply");
        JButton cancel = new JButton("Cancel");
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.RIGHT));
        controls.add(apply);
        controls.add(cancel);
        JPanel addRowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addRowPanel.add(addRow);

        JTextArea help = new JTextArea(renderRecordFilterProfileHelp(profile));
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setBorder(BorderFactory.createEtchedBorder());
        installTextAreaClipboardSupport(help);

        apply.addActionListener(e -> {
	try {
                String serialized = serializeRecordFilterRows(rows);
                RecordFilterSpec normalized = RecordFilterSpec.parse(serialized);
                configuredRecordFilters[0] = normalized.toPropertyString();
                updateRecordFilterSummary(recordFilterSummary, configuredRecordFilters[0]);
                clearRecordFilters.setEnabled(!configuredRecordFilters[0].isBlank());
                dialog.dispose();
	} catch (AppException ex) {
                JOptionPane.showMessageDialog(
		dialog,
		ex.getMessage(),
		"Invalid record filter",
		JOptionPane.ERROR_MESSAGE);
	}
        });
        cancel.addActionListener(e -> dialog.dispose());

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(help, BorderLayout.NORTH);
        JPanel rowsContent = new JPanel(new BorderLayout(8, 8));
        rowsContent.add(addRowPanel, BorderLayout.NORTH);
        rowsContent.add(new JScrollPane(rowsPanel), BorderLayout.CENTER);
        content.add(rowsContent, BorderLayout.CENTER);

        dialog.add(content, BorderLayout.CENTER);
        dialog.add(controls, BorderLayout.SOUTH);
        dialog.setSize(760, 420);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    /**
     * Adds one editable filter row to the record-filter dialog.
     *
     * @param rowsPanel parent panel the row is appended to
     * @param profile available terms and value suggestions
     * @param initialField initially selected field, or {@code null}
     * @param initialValues initial value text
     * @return the row widgets for later serialization
     */
    private static RecordFilterRowWidgets addRecordFilterRow(
	JPanel rowsPanel,
	RecordFilterDatasetProfile profile,
	String initialField,
	String initialValues) {
        JPanel row = new JPanel(new BorderLayout(8, 8));
        List<String> selectableTerms = new ArrayList<>();
        selectableTerms.add("");
        selectableTerms.addAll(profile.availableTerms());
        JComboBox<String> fieldChoice = new JComboBox<>(selectableTerms.toArray(String[]::new));
        fieldChoice.setMaximumRowCount(20);
        fieldChoice.setPrototypeDisplayValue(longestSelectableTerm(selectableTerms));
        String unresolvedMessage = null;
        String unresolvedField = null;
        if (initialField != null) {
	DarwinCoreTermResolver.Resolution resolution = DarwinCoreTermResolver.resolve(
			initialField,
			DarwinCoreTermResolver.indexAvailableTerms(profile.availableTerms()));
	if (!resolution.isAmbiguous() && resolution.preferredMatch() != null) {
		fieldChoice.setSelectedItem(resolution.preferredMatch());
	} else {
		unresolvedField = initialField;
		unresolvedMessage = resolution.isAmbiguous()
				? "Saved filter field \"" + initialField
						+ "\" matches multiple fields in the currently selected dataset.\nChoose an exact dataset field or remove this row."
				: "Saved filter field \"" + initialField
						+ "\" is not present in the currently selected dataset.\nChoose a dataset field or remove this row.";
	}
        }
        JTextField values = new JTextField(initialValues == null ? "" : initialValues);
        forceSingleLineControlHeight(fieldChoice, values.getPreferredSize().height);
        forceSingleLineControlHeight(values, values.getPreferredSize().height);
        JTextArea suggestionArea = new JTextArea(8, 30);
        suggestionArea.setEditable(false);
        suggestionArea.setLineWrap(true);
        suggestionArea.setWrapStyleWord(true);
        suggestionArea.setBorder(BorderFactory.createEtchedBorder());
        installTextAreaClipboardSupport(suggestionArea);
        JScrollPane suggestionScroll = new JScrollPane(suggestionArea);
        lockTextAreaHeight(suggestionScroll, suggestionArea.getPreferredSize().height + 60);
        JButton remove = new JButton("Remove");
        forceSingleLineControlHeight(remove, values.getPreferredSize().height);
        remove.addActionListener(e -> {
	rowsPanel.remove(row);
	rowsPanel.revalidate();
	rowsPanel.repaint();
        });

        String unresolvedSelection = unresolvedMessage;
        fieldChoice.addActionListener(e -> suggestionArea.setText(
                renderRecordFilterValueSuggestions(profile, (String) fieldChoice.getSelectedItem(), unresolvedSelection)));
        suggestionArea.setText(renderRecordFilterValueSuggestions(
                profile,
                (String) fieldChoice.getSelectedItem(),
                unresolvedSelection));

        JPanel inputRow = new JPanel(new BorderLayout(8, 8));
        inputRow.add(new JLabel("Field"), BorderLayout.WEST);
        inputRow.add(fieldChoice, BorderLayout.CENTER);
        inputRow.add(remove, BorderLayout.EAST);
        JPanel valuesRow = new JPanel(new BorderLayout(8, 8));
        valuesRow.add(new JLabel("Values"), BorderLayout.WEST);
        valuesRow.add(values, BorderLayout.CENTER);

        JPanel stacked = new JPanel();
        stacked.setLayout(new BoxLayout(stacked, BoxLayout.Y_AXIS));
        stacked.add(inputRow);
        stacked.add(valuesRow);
        stacked.add(suggestionScroll);

        row.add(stacked, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        rowsPanel.add(row);
        return new RecordFilterRowWidgets(row, fieldChoice, values, unresolvedField, unresolvedMessage);
    }

    /**
     * Profiles a dataset for record-filter construction by collecting available terms and the most
     * common values seen for each term.
     *
     * @param dataset the dataset to inspect
     * @return the dataset profile used by the filter dialog
     */
    private static RecordFilterDatasetProfile profileRecordFilters(RecordDataset dataset) {
        Map<String, Map<String, Long>> countsByTerm = new LinkedHashMap<>();
        dataset.records().forEach(record -> record.terms().forEach((term, value) -> {
	countsByTerm.computeIfAbsent(term, ignored -> new LinkedHashMap<>());
	if (value != null && !value.isBlank()) {
                countsByTerm.get(term).merge(value, 1L, Long::sum);
	}
        }));
        List<String> availableTerms = new ArrayList<>(countsByTerm.keySet());
        Map<String, List<RecordFilterValueOption>> topValuesByTerm = new LinkedHashMap<>();
        Map<String, Integer> distinctValueCounts = new LinkedHashMap<>();
        countsByTerm.forEach((term, counts) -> {
	List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
	entries.sort(java.util.Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
	.reversed()
	.thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
	distinctValueCounts.put(term, entries.size());
	topValuesByTerm.put(term, entries.stream()
	.limit(RECORD_FILTER_SUGGESTION_LIMIT)
	.map(entry -> new RecordFilterValueOption(entry.getKey(), entry.getValue()))
	.toList());
        });
        return new RecordFilterDatasetProfile(dataset.records().size(), List.copyOf(availableTerms), topValuesByTerm, distinctValueCounts);
    }

    /**
     * Renders the read-only setup summary for the current serialized record filters.
     *
     * @param summaryArea setup-screen summary area
     * @param serializedRecordFilters current serialized filter string
     */
    private static void updateRecordFilterSummary(JTextArea summaryArea, String serializedRecordFilters) {
        summaryArea.setText(renderRecordFilterSelectionSummary(serializedRecordFilters));
    }

    /**
     * Renders the current record-filter selection for the setup screen.
     *
     * @param serializedRecordFilters current serialized filter string
     * @return the rendered setup summary
     */
    private static String renderRecordFilterSelectionSummary(String serializedRecordFilters) {
        RecordFilterSpec spec = RecordFilterSpec.parse(serializedRecordFilters);
        if (spec.criteria().isEmpty()) {
	return "No record filters configured.\nUse \"Build Record Filters...\" to choose terms present in the selected dataset.";
        }
        StringBuilder builder = new StringBuilder("Configured record filters\n");
        spec.criteria().forEach((field, values) -> builder.append(" - ")
                .append(field)
                .append(" = ")
                .append(String.join(" | ", values))
                .append('\n'));
        builder.append("Within a field, values are ORed; across fields, filters are ANDed.");
        return builder.toString();
    }

    /**
     * Renders the record-filter dialog's dataset overview help text.
     *
     * @param profile dataset profile informing the dialog
     * @return the rendered help text
     */
    private static String renderRecordFilterProfileHelp(RecordFilterDatasetProfile profile) {
        return "Select terms present in the dataset and enter exact values to match.\n"
                + "Loaded " + profile.recordCount() + " records and " + profile.availableTerms().size() + " distinct terms.\n"
                + "Within a field, separate multiple values with |. Across fields, filters are ANDed.";
    }

    /**
     * Renders common value suggestions for one dataset term in the record-filter dialog.
     *
     * @param profile dataset profile informing the dialog
     * @param term selected term name
     * @return the rendered suggestions
     */
    private static String renderRecordFilterValueSuggestions(
	RecordFilterDatasetProfile profile,
	String term,
	String unresolvedMessage) {
        if (term == null || term.isBlank()) {
	if (unresolvedMessage != null && !unresolvedMessage.isBlank()) {
		return unresolvedMessage;
	}
	return "Choose a field to see common values present in the dataset.";
        }
        StringBuilder builder = new StringBuilder("Common values for ").append(term).append('\n');
        List<RecordFilterValueOption> values = profile.topValuesByTerm().getOrDefault(term, List.of());
        if (values.isEmpty()) {
	builder.append(" - no values observed");
	return builder.toString();
        }
        values.forEach(option -> builder.append(" - ").append(option.value()).append(" (").append(option.count()).append(")\n"));
        int distinctValueCount = profile.distinctValueCounts().getOrDefault(term, values.size());
        if (distinctValueCount > values.size()) {
	builder.append(" ... and ").append(distinctValueCount - values.size()).append(" more distinct value(s)");
        } else if (builder.charAt(builder.length() - 1) == '\n') {
	builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    /**
     * Serializes the visible rows from the record-filter dialog into property form.
     *
     * @param rows the row widgets to serialize
     * @return the serialized record-filter string
     */
    private static String serializeRecordFilterRows(List<RecordFilterRowWidgets> rows) {
        List<String> clauses = new ArrayList<>();
        for (RecordFilterRowWidgets row : rows) {
	if (row.container().getParent() == null) {
                continue;
	}
	String field = ((String) row.fieldChoice().getSelectedItem());
	String values = row.valuesField().getText().trim();
	if ((field == null || field.isBlank()) && values.isBlank()) {
                continue;
	}
	if (row.unresolvedField() != null && (field == null || field.isBlank())) {
                throw new AppException("Invalid record filter: select a replacement for " + row.unresolvedField()
		+ " or remove that filter row");
	}
	if (field == null || field.isBlank()) {
                throw new AppException("Invalid record filter: field name must not be blank");
	}
	if (values.isBlank()) {
                throw new AppException("Invalid record filter for " + field + ": values must not be blank");
	}
	clauses.add(field + "=" + values.replace(" | ", "|"));
        }
        return String.join("; ", clauses);
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
     * Forces a combo box to keep a single-line control height instead of expanding vertically with
     * layout changes or long item lists.
     *
     * @param combo the combo box to normalize
     * @param targetHeight the desired control height in pixels
     */
    private static void forceSingleLineControlHeight(JComboBox<?> combo, int targetHeight) {
        Dimension preferred = combo.getPreferredSize();
        int width = Math.max(preferred.width, 220);
        Dimension normalized = new Dimension(width, targetHeight);
        combo.setPreferredSize(normalized);
        combo.setMinimumSize(normalized);
        combo.setMaximumSize(normalized);
    }

    /**
     * Forces a text field to keep a single-line control height.
     *
     * @param textField the text field to normalize
     * @param targetHeight the desired control height in pixels
     */
    private static void forceSingleLineControlHeight(JTextField textField, int targetHeight) {
        Dimension preferred = textField.getPreferredSize();
        Dimension normalized = new Dimension(preferred.width, targetHeight);
        textField.setPreferredSize(normalized);
        textField.setMinimumSize(normalized);
        textField.setMaximumSize(new Dimension(Integer.MAX_VALUE, targetHeight));
    }

    /**
     * Forces a button to keep a single-line control height.
     *
     * @param button the button to normalize
     * @param targetHeight the desired control height in pixels
     */
    private static void forceSingleLineControlHeight(JButton button, int targetHeight) {
        Dimension preferred = button.getPreferredSize();
        Dimension normalized = new Dimension(preferred.width, targetHeight);
        button.setPreferredSize(normalized);
        button.setMinimumSize(normalized);
        button.setMaximumSize(normalized);
    }

    /**
     * Fixes a scroll pane's preferred/minimum/maximum height so dynamic wrapped text does not cause
     * surrounding filter rows to resize and scroll the selected controls out of view.
     *
     * @param scrollPane the scroll pane whose height should remain stable
     * @param targetHeight the desired control height in pixels
     */
    private static void lockTextAreaHeight(JScrollPane scrollPane, int targetHeight) {
        Dimension preferred = scrollPane.getPreferredSize();
        Dimension normalized = new Dimension(Math.max(preferred.width, 320), targetHeight);
        scrollPane.setPreferredSize(normalized);
        scrollPane.setMinimumSize(normalized);
        scrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, normalized.height));
    }

    /**
     * Returns a stable prototype display value for a field-selection combo box so its width does
     * not change when different terms are selected.
     *
     * @param selectableTerms all candidate terms shown by the combo box
     * @return the longest available term, or a short blank placeholder when no terms exist
     */
    private static String longestSelectableTerm(List<String> selectableTerms) {
        return selectableTerms.stream()
                .max(java.util.Comparator.comparingInt(String::length))
                .filter(value -> !value.isBlank())
                .orElse("Select field");
    }

    /**
     * Switches the monitor page's content area between the standard three-pane results view and
     * the workflow-visualization view, while keeping the header and bottom button row in place.
     *
     * @param cards the card layout controlling the monitor content region
     * @param contentPanel the monitor content panel managed by {@code cards}
     * @param toggleButton the button whose label reflects the current state
     * @param showingWorkflow single-element state holder updated in place
     * @param showWorkflow whether the workflow visualization should be shown
     */
    private static void showWorkflowVisualization(
            CardLayout cards,
            JPanel contentPanel,
            JButton toggleButton,
            boolean[] showingWorkflow,
            boolean showWorkflow) {
        cards.show(contentPanel, showWorkflow ? "workflow" : "results");
        showingWorkflow[0] = showWorkflow;
        toggleButton.setText(showWorkflow ? "Hide Workflow Visualization" : "Show Workflow Visualization");
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
        return CachedResourceResolver.cacheNameFor(source);
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
                rebound,
                preparedRun.filterSummary());
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
                        && discovered.implementationMethod().equals(binding.implementationMethod())
                        && discovered.parameters().size() == binding.parameterBindings().size()
                        && java.util.stream.IntStream.range(0, discovered.parameters().size()).allMatch(index -> {
                            org.filteredpush.bdq_workbench.model.MethodParameter discoveredParameter = discovered.parameters().get(index);
                            org.filteredpush.bdq_workbench.model.MethodParameter boundParameter = binding.parameterBindings().get(index).parameter();
                            return discoveredParameter.index() == boundParameter.index()
                                    && discoveredParameter.role() == boundParameter.role()
                                    && java.util.Objects.equals(discoveredParameter.source(), boundParameter.source())
                                    && java.util.Objects.equals(discoveredParameter.typeName(), boundParameter.typeName());
                        }))
                .findFirst()
                .orElseThrow(() -> new AppException("No discovered implementation found for "
                        + binding.fullImplementationSignature()));
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
                + "Saved files: reports/bdq-report-summary.txt, reports/bdq-report-responses.txt, reports/bdq-report-xls.xlsx, reports/bdq-report-xls-unresolved.xlsx, reports/bdq-report-rdf.ttl\n";
    }

    /**
     * Renders the monitor page's simple stage-status view.
     *
     * @param preparedRun the prepared run being reviewed or executed
     * @param activePhase the phase currently running, or {@code null} when no execution phase is active
     * @param exportRunning whether stage 9 (report export/finalization) is in progress
     * @param runCompleted whether the run has finished successfully
     * @param runFailed whether the run has failed
     * @return a multi-line stage overview
     */
    private static String renderStageOverview(
            PreparedRun preparedRun,
            Phase activePhase,
            boolean exportRunning,
            boolean runCompleted,
            boolean runFailed) {
        int completedStages = completedWorkflowStageCount(preparedRun, activePhase, exportRunning, runCompleted, runFailed);
        List<WorkflowStageStatus> stages = workflowStageStatuses(preparedRun, activePhase, exportRunning, runCompleted, runFailed);
        StringBuilder builder = new StringBuilder("Process stages\n");
        builder.append("Workflow progress: ")
                .append(completedStages)
                .append("/")
                .append(totalWorkflowStageCount())
                .append(" stages completed\n");
        if (preparedRun != null && (activePhase != null || exportRunning) && !runCompleted && !runFailed) {
            builder.append("Current stage: ")
                    .append(exportRunning ? "Export reports" : activePhase)
                    .append(" (stage ")
                    .append(currentWorkflowStageNumber(preparedRun, activePhase, exportRunning, false))
                    .append("/")
                    .append(totalWorkflowStageCount())
                    .append(")\n");
        }
        stages.forEach(stage -> builder.append(stageLine(stage.name(), stage.state(), stage.detail())).append('\n'));
        return builder.toString();
    }

    /**
     * Appends record-filter details to the preflight summary.
     *
     * @param builder the summary under construction
     * @param filterSummary the filter outcome to describe
     */
    private static void appendRecordFilterSummary(StringBuilder builder, RecordFilterSummary filterSummary) {
        builder.append("Input records loaded: ").append(filterSummary.originalRecordCount()).append('\n');
        builder.append("Records selected for execution: ").append(filterSummary.filteredRecordCount()).append('\n');
        builder.append("Records excluded by filters: ").append(filterSummary.excludedRecordCount()).append('\n');
        builder.append("Active record filters:\n");
        if (filterSummary.resolvedCriteria().isEmpty()) {
            builder.append(" - none\n");
        } else {
            filterSummary.resolvedCriteria().forEach((field, values) ->
                    builder.append(" - ").append(field).append(" = ").append(String.join(" | ", values)).append('\n'));
        }
        filterSummary.diagnostics().forEach(diagnostic -> builder.append(" - ").append(diagnostic).append('\n'));
        builder.append('\n');
    }

    /**
     * Renders one stage line with a state and detail string.
     *
     * @param stageName the stage name
     * @param state the stage state
     * @param detail the detail string
     * @return the formatted stage line
     */
    private static String stageLine(String stageName, String state, String detail) {
        return String.format("[%s] %s - %s", state, stageName, detail);
    }

    /**
     * Builds the set of coarse workflow stages shown in the monitor UI.
     *
     * @param preparedRun the run being reviewed or executed
     * @param activePhase the currently active phase, if any
     * @param exportRunning whether stage 9 (report export/finalization) is in progress
     * @param runCompleted whether execution has completed
     * @param runFailed whether execution has failed
     * @return ordered stage statuses for the overall workflow
     */
    private static List<WorkflowStageStatus> workflowStageStatuses(
            PreparedRun preparedRun,
            Phase activePhase,
            boolean exportRunning,
            boolean runCompleted,
            boolean runFailed) {
        RecordFilterSummary filterSummary = preparedRun == null ? RecordFilterSummary.unfiltered(new RecordDataset(List.of()))
                : preparedRun.filterSummary();
        ExecutionPlan plan = preparedRun == null
                ? new ExecutionPlan(new UseCase("", "", ""), new Policy("", List.of()), List.of(), List.of())
                : preparedRun.plan();
        int runnable = preparedRun == null ? 0 : preparedRun.bindingResult().runnableBindings().size();
        int unresolved = preparedRun == null
                ? 0
                : preparedRun.plan().unresolvedTests().size() + preparedRun.bindingResult().unresolved().size();
        List<WorkflowStageStatus> stages = new ArrayList<>();
        stages.add(new WorkflowStageStatus(
                "Load dataset",
                "completed",
                filterSummary.originalRecordCount() + " records loaded",
                100));
        stages.add(new WorkflowStageStatus(
                "Apply record filters",
                filterSummary.hasActiveFilters() ? "completed" : "skipped",
                filterSummary.filteredRecordCount() + " kept, " + filterSummary.excludedRecordCount() + " excluded",
                100));
        stages.add(new WorkflowStageStatus(
                "Resolve use case/policy",
                preparedRun == null ? "pending" : "completed",
                plan.tests().size() + plan.unresolvedTests().size() + " policy tests",
                preparedRun == null ? 0 : 100));
        stages.add(new WorkflowStageStatus(
                "Discover implementations",
                preparedRun == null ? "pending" : "completed",
                preparedRun == null ? "0 discovered" : preparedRun.discovered().size() + " discovered",
                preparedRun == null ? 0 : 100));
        stages.add(new WorkflowStageStatus(
                "Bind tests / validate parameters",
                preparedRun == null ? "pending" : "completed",
                runnable + " runnable, " + unresolved + " unresolved",
                preparedRun == null ? 0 : 100));
        stages.add(workflowStageStatusForPhase(Phase.PRE_AMENDMENT, activePhase, exportRunning, runCompleted, runFailed));
        stages.add(workflowStageStatusForPhase(Phase.AMENDMENT, activePhase, exportRunning, runCompleted, runFailed));
        stages.add(workflowStageStatusForPhase(Phase.POST_AMENDMENT, activePhase, exportRunning, runCompleted, runFailed));
        stages.add(new WorkflowStageStatus(
                "Export reports",
                runCompleted ? "completed" : exportRunning ? "running" : runFailed ? "failed" : "pending",
                runCompleted ? "reports written" : exportRunning ? "reports in progress" : "reports not written yet",
                runCompleted ? 100 : exportRunning ? 50 : runFailed ? 25 : 0));
        return List.copyOf(stages);
    }

    /**
     * Builds one execution-phase workflow stage status.
     *
     * @param phase the phase to render
     * @param activePhase the currently active phase, if any
     * @param exportRunning whether stage 9 (report export/finalization) is in progress
     * @param runCompleted whether execution has completed
     * @param runFailed whether execution has failed
     * @return the formatted phase state for the workflow UI
     */
    private static WorkflowStageStatus workflowStageStatusForPhase(
            Phase phase,
            Phase activePhase,
            boolean exportRunning,
            boolean runCompleted,
            boolean runFailed) {
        String state;
        String detail;
        int progressPercent;
        if (runCompleted || exportRunning || (runFailed && activePhase == null)) {
            state = "completed";
            detail = "phase complete";
            progressPercent = 100;
        } else if (runFailed && phase == activePhase) {
            state = "failed";
            detail = "phase incomplete";
            progressPercent = 25;
        } else if (activePhase != null && phase.ordinal() < activePhase.ordinal()) {
            state = "completed";
            detail = "phase complete";
            progressPercent = 100;
        } else if (phase == activePhase) {
            state = "running";
            detail = "phase in progress";
            progressPercent = 50;
        } else {
            state = "pending";
            detail = "phase not started";
            progressPercent = 0;
        }
        return new WorkflowStageStatus(phase.name(), state, detail, progressPercent);
    }

    /**
     * Returns the total number of coarse workflow stages shown in the monitor UI.
     *
     * @return the total number of displayed stages
     */
    private static int totalWorkflowStageCount() {
        return 9;
    }

    /**
     * Counts how many displayed workflow stages have completed so far.
     *
     * @param preparedRun the run being displayed
     * @param activePhase the currently running phase, if any
     * @param exportRunning whether stage 9 (report export/finalization) is in progress
     * @param runCompleted whether the full run has completed
     * @param runFailed whether the full run has failed after execution phases completed
     * @return the number of completed stages in the monitor view
     */
    private static int completedWorkflowStageCount(
            PreparedRun preparedRun,
            Phase activePhase,
            boolean exportRunning,
            boolean runCompleted,
            boolean runFailed) {
        if (preparedRun == null) {
            return 0;
        }
        if (runCompleted) {
            return totalWorkflowStageCount();
        }
        int completed = 5;
        if (activePhase != null) {
            completed += activePhase.ordinal();
        } else if (exportRunning) {
            completed = totalWorkflowStageCount() - 1;
        } else if (runFailed) {
            completed = totalWorkflowStageCount() - 1;
        }
        return completed;
    }

    /**
     * Returns the currently active one-based workflow stage number for progress display.
     *
     * @param preparedRun the run being displayed
     * @param activePhase the currently running phase, if any
     * @param exportRunning whether stage 9 (report export/finalization) is in progress
     * @param runCompleted whether the full run has completed
     * @return the one-based stage number currently in progress or just completed
     */
    private static int currentWorkflowStageNumber(
            PreparedRun preparedRun,
            Phase activePhase,
            boolean exportRunning,
            boolean runCompleted) {
        int completed = completedWorkflowStageCount(preparedRun, activePhase, exportRunning, runCompleted, false);
        if (runCompleted || preparedRun == null) {
            return completed;
        }
        return Math.min(totalWorkflowStageCount(), completed + 1);
    }

    /**
     * Resets the workflow-visualization pane to its pre-run placeholder.
     *
     * @param panel the visualization panel to reset
     */
    private static void resetWorkflowVisualizationPanel(JPanel panel) {
        panel.removeAll();
        JLabel placeholder = new JLabel("Workflow visualization becomes available after the run completes.");
        placeholder.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        panel.add(placeholder);
        panel.revalidate();
        panel.repaint();
    }

    /**
     * Populates the workflow-visualization pane with graphical summaries of workflow stages and
     * multi-record COUNT measures.
     *
     * @param panel the visualization panel to populate
     * @param preparedRun the completed run whose setup/filter/binding counts are summarized
     * @param summary the completed execution summary
     */
    private static void updateWorkflowVisualizationPanel(JPanel panel, PreparedRun preparedRun, ExecutionSummary summary) {
        panel.removeAll();
        JLabel title = new JLabel("Workflow Visualization");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, title.getFont().getSize() + 3f));
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        panel.add(title);
        panel.add(createVisualizationProgressRow(
                "Overall workflow progress",
                totalWorkflowStageCount(),
                totalWorkflowStageCount(),
                totalWorkflowStageCount() + "/" + totalWorkflowStageCount() + " stages completed",
                Color.decode("#2e7d32")));

        RecordFilterSummary filterSummary = preparedRun.filterSummary();
        int originalCount = Math.max(1, filterSummary.originalRecordCount());
        panel.add(createVisualizationProgressRow(
                "Records selected for execution",
                filterSummary.filteredRecordCount(),
                originalCount,
                filterSummary.filteredRecordCount() + " kept, " + filterSummary.excludedRecordCount() + " excluded",
                Color.decode("#1565c0")));

        int runnable = preparedRun.bindingResult().runnableBindings().size();
        int unresolved = preparedRun.plan().unresolvedTests().size() + preparedRun.bindingResult().unresolved().size();
        panel.add(createVisualizationProgressRow(
                "Runnable test bindings",
                runnable,
                Math.max(1, runnable + unresolved),
                runnable + " runnable, " + unresolved + " unresolved",
                unresolved == 0 ? Color.decode("#2e7d32") : Color.decode("#ef6c00")));

        JLabel stagesLabel = new JLabel("Process stages");
        stagesLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        panel.add(stagesLabel);
        workflowStageStatuses(preparedRun, null, false, true, false).forEach(stage ->
                panel.add(createVisualizationProgressRow(
                        stage.name(),
                        stage.progressPercent(),
                        100,
                        stage.state() + " - " + stage.detail(),
                        colorForStageState(stage.state()))));

        JLabel measureLabel = new JLabel("Multi-record COUNT measures");
        measureLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        panel.add(measureLabel);
        List<CountMeasureSummary> countMeasures = summarizeCountMeasures(summary);
        if (countMeasures.isEmpty()) {
            panel.add(new JLabel("No multi-record COUNT measures were produced."));
        } else {
            countMeasures.forEach(measure -> {
                JLabel label = new JLabel(measure.label());
                label.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
                panel.add(label);
                panel.add(createVisualizationProgressRow(
                        "Pre-amendment",
                        measure.prePercent(),
                        100,
                        measure.preText(),
                        Color.decode("#6a1b9a")));
                panel.add(createVisualizationProgressRow(
                        "Post-amendment",
                        measure.postPercent(),
                        100,
                        measure.postText(),
                        Color.decode("#00897b")));
            });
        }
        panel.revalidate();
        panel.repaint();
    }

    /**
     * Builds one visualization row consisting of a label and a progress bar whose string carries
     * the detail summary.
     *
     * @param labelText left-side row label
     * @param value current value represented by the progress bar
     * @param max maximum value represented by the progress bar
     * @param detail detail text shown on the progress bar
     * @param color progress-bar foreground color
     * @return a reusable panel for the workflow-visualization pane
     */
    private static JPanel createVisualizationProgressRow(
            String labelText,
            int value,
            int max,
            String detail,
            Color color) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        row.add(new JLabel(labelText), BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar(0, Math.max(1, max));
        bar.setValue(Math.max(0, Math.min(value, Math.max(1, max))));
        bar.setStringPainted(true);
        bar.setString(detail);
        bar.setForeground(color);
        row.add(bar, BorderLayout.CENTER);
        return row;
    }

    /**
     * Chooses a visualization color for a stage state.
     *
     * @param state the stage state string
     * @return the color associated with that state
     */
    private static Color colorForStageState(String state) {
        return switch (state) {
            case "completed" -> Color.decode("#2e7d32");
            case "running" -> Color.decode("#1565c0");
            case "failed" -> Color.decode("#c62828");
            case "skipped" -> Color.decode("#616161");
            default -> Color.decode("#9e9e9e");
        };
    }

    /**
     * Summarizes built-in multi-record COUNT measures for the workflow-visualization pane.
     *
     * @param summary the completed execution summary
     * @return one summary per COUNT measure, ordered by label
     */
    private static List<CountMeasureSummary> summarizeCountMeasures(ExecutionSummary summary) {
        Map<String, Map<Phase, Response>> byTest = new LinkedHashMap<>();
        summary.multiRecordMeasureResponses().stream()
                .filter(response -> BuiltInMeasureSpec.MeasureKind.COUNT.name().equals(
                        response.parameters().get(BuiltInMeasureSpec.KIND_KEY)))
                .forEach(response -> byTest
                        .computeIfAbsent(response.testId(), ignored -> new LinkedHashMap<>())
                        .put(response.phase(), response));
        List<CountMeasureSummary> summaries = new ArrayList<>();
        byTest.values().forEach(byPhase -> {
            Response example = byPhase.values().stream().findFirst().orElse(null);
            if (example == null) {
                return;
            }
            String label = example.parameters().getOrDefault(BuiltInMeasureSpec.MEASURE_LABEL_KEY, example.testId());
            Response pre = byPhase.get(Phase.PRE_AMENDMENT);
            Response post = byPhase.get(Phase.POST_AMENDMENT);
            summaries.add(new CountMeasureSummary(
                    label,
                    extractMeasurePercentage(pre),
                    renderCountMeasurePhaseText(pre),
                    extractMeasurePercentage(post),
                    renderCountMeasurePhaseText(post)));
        });
        summaries.sort(java.util.Comparator.comparing(CountMeasureSummary::label, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(summaries);
    }

    /**
     * Renders a COUNT measure's value as {@code "<count>/<total> (<percentage>%)"} for the
     * workflow visualization.
     *
     * @param response the measure response to render
     * @return the display text for that measure phase
     */
    private static String renderCountMeasurePhaseText(Response response) {
        if (response == null) {
            return "not run";
        }
        String count = response.parameters().getOrDefault(BuiltInMeasureSpec.MATCHING_COUNT_KEY, response.responseResult());
        String total = response.parameters().getOrDefault(BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "?");
        String percentage = response.parameters().get(BuiltInMeasureSpec.PERCENTAGE_KEY);
        return percentage == null || percentage.isBlank()
                ? count + "/" + total
                : count + "/" + total + " (" + percentage + "%)";
    }

    /**
     * Extracts a COUNT measure's percentage for progress-bar display.
     *
     * @param response the measure response to inspect
     * @return the percentage, clamped to the range {@code 0..100}
     */
    private static int extractMeasurePercentage(Response response) {
        if (response == null) {
            return 0;
        }
        String percentage = response.parameters().get(BuiltInMeasureSpec.PERCENTAGE_KEY);
        if (percentage != null && !percentage.isBlank()) {
            try {
                return Math.max(0, Math.min(100, (int) Math.round(Double.parseDouble(percentage))));
            } catch (NumberFormatException ignored) {
                // fall through to derived percentage
            }
        }
        String count = response.parameters().get(BuiltInMeasureSpec.MATCHING_COUNT_KEY);
        String total = response.parameters().get(BuiltInMeasureSpec.TOTAL_RECORDS_KEY);
        if (count != null && total != null) {
            try {
                int countValue = Integer.parseInt(count);
                int totalValue = Integer.parseInt(total);
                if (totalValue > 0) {
                    return Math.max(0, Math.min(100, (int) Math.round((countValue * 100.0d) / totalValue)));
                }
            } catch (NumberFormatException ignored) {
                // keep fallback
            }
        }
        return 0;
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
                state[0].preparedRun().bindingResult().runnableBindings().size(),
                state[0].preparedRun().bindingResult().unresolved().size());
        setStatus(statusArea, renderPreflightMessage(state[0]));
        bindingGrid.setModel(new BindingReviewTableModel(state[0].preparedRun().bindingResult().reviews()));
        configureBindingGrid(bindingGrid);
        resultSummaryArea.setText(renderStageOverview(preparedRun, null, false, false, false)
                + "\nParameter review ready. Edit parameter values, right-click a single test row to inspect it or run it in isolation, or save/load settings before starting the run.\n");
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

    /** One profiled value suggestion for a dataset term in the record-filter dialog. */
    private record RecordFilterValueOption(String value, long count) {
    }

	/** One requested-term scope option for the dataset-view builder dialog. */
	private record DatasetViewUseCaseScope(
			String label,
			List<String> requestedTerms,
			List<UseCaseChoice> applicableUseCases) {
		@Override
		public String toString() {
			return label;
		}
	}

	/** Prepared relational schema and suggestion payload for the dataset-view builder dialog. */
	private record DatasetViewPreview(
			RelationalIngestResult relational,
			DatasetSchema schema,
			DatasetView suggested,
			List<UseCaseChoice> availableUseCases,
			String selectedUseCaseId,
			List<String> selectedTerms,
			List<String> allTerms) {
	}

    /** Dataset-derived terms and value counts used to build record filters interactively. */
    private record RecordFilterDatasetProfile(
		int recordCount,
		List<String> availableTerms,
		Map<String, List<RecordFilterValueOption>> topValuesByTerm,
		Map<String, Integer> distinctValueCounts) {
    }

    /** One coarse workflow stage's state, detail text, and visualization percentage. */
    private record WorkflowStageStatus(String name, String state, String detail, int progressPercent) {
    }

    /** One summarized multi-record COUNT measure for the workflow-visualization pane. */
    private record CountMeasureSummary(String label, int prePercent, String preText, int postPercent, String postText) {
    }

    /** Swing widgets for one editable row in the record-filter dialog. */
    private record RecordFilterRowWidgets(
		JPanel container,
		JComboBox<String> fieldChoice,
		JTextField valuesField,
		String unresolvedField,
		String unresolvedMessage) {
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
