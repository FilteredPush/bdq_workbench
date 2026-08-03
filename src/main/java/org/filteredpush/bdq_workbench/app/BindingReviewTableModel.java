/** BindingReviewTableModel.java
 *
 * Swing table model backing the preflight binding review grid, exposing per-test binding
 * status, editable parameter values, and pre/post-amendment output for display in the GUI.
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.table.AbstractTableModel;
import org.filteredpush.bdq_workbench.model.BindingReview;

/**
 * Table model for preflight binding review and parameter edits.
 *
 * <p>Wraps one {@link BindingReview} per row, fulfilling the {@link AbstractTableModel}
 * contract with ten fixed columns describing the test, its implementation/binding status, its
 * parameterization capability, and (where the review's
 * {@link org.filteredpush.bdq_workbench.model.ParameterizationCapability} allows it) an
 * editable "use defaults" flag and parameter-value string, plus pre- and post-amendment
 * preview output populated after a preflight sample execution.
 *
 * <p>Column 6 ("Use Defaults") and column 7 ("Parameter Values") are editable only for rows
 * whose review {@link #supportsParameterEditing(int) supports parameter editing}; editing a
 * cell updates the row's in-memory {@link ParameterSettings} and fires a
 * {@code TableRowsUpdated} event. Edited settings can be read back per test via
 * {@link #settingsFor(String)} or {@link #parameterSettings()}, and restored in bulk via
 * {@link #applyParameterSettings(Map)}.
 */
public class BindingReviewTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            "Test Label/Id",
            "Type",
            "Implementation",
            "Binding",
            "Capability",
            "Chosen Method",
            "Use Defaults",
            "Parameter Values",
            "Pre-Amendment",
            "Post-amendment"
    };

    private final List<RowState> rows = new ArrayList<>();

    /**
     * Creates a table model with one row per review, in the given order, initializing each
     * row's editable parameter state from {@link BindingReview#usingDefaultParameters()} and
     * {@link BindingReview#parameterValues()}.
     *
     * @param reviews the preflight binding reviews to display, one per test
     */
    public BindingReviewTableModel(List<BindingReview> reviews) {
        reviews.forEach(review -> rows.add(new RowState(review)));
    }

    /**
     * @return the number of reviews backing this model
     */
    @Override
    public int getRowCount() {
        return rows.size();
    }

    /**
     * @return the fixed number of columns ({@link #COLUMNS}.length)
     */
    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    /**
     * @param column the column index
     * @return the display header for the given column
     */
    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    /**
     * @param columnIndex the column index
     * @return {@link Boolean} for the "Use Defaults" column (index 6), {@link String} otherwise
     */
    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == 6 ? Boolean.class : String.class;
    }

    /**
     * @param rowIndex the row index
     * @param columnIndex the column index
     * @return {@code true} for the "Use Defaults" (6) and "Parameter Values" (7) columns when
     *     the row's review {@link #supportsParameterEditing(int) supports parameter editing};
     *     {@code false} otherwise
     */
    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return supportsParameterEditing(rowIndex) && (columnIndex == 6 || columnIndex == 7);
    }

    /**
     * @param rowIndex the row index
     * @param columnIndex the column index
     * @return the display value for the given cell: test label/id, type, implementation
     *     status, binding status, parameterization capability, chosen implementation method,
     *     the "use defaults" flag, the parameter values as a {@code key=value; ...} string, or
     *     the pre-/post-amendment preview output, depending on {@code columnIndex}
     */
    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        RowState row = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.review.test().label() + " / " + row.review.test().id();
            case 1 -> row.review.test().type().name();
            case 2 -> row.review.implementationStatus().name();
            case 3 -> row.review.bindingStatus().name();
            case 4 -> row.review.parameterizationCapability() == org.filteredpush.bdq_workbench.model.ParameterizationCapability.BOTH
                    ? "PARAMETERIZED_VERSION_AVAILABLE"
                    : row.review.parameterizationCapability().name();
            case 5 -> row.review.chosenImplementationMethod();
            case 6 -> supportsParameterEditing(rowIndex) ? row.useDefaults : null;
            case 7 -> supportsParameterEditing(rowIndex) ? toDisplayValue(row.parameters) : "";
            case 8 -> row.preAmendmentOutput;
            case 9 -> row.postAmendmentOutput;
            default -> "";
        };
    }

    /**
     * Applies an edit to the "Use Defaults" (6) or "Parameter Values" (7) column, then fires a
     * {@code TableRowsUpdated} event for the affected row. Setting "Use Defaults" to true
     * clears the row's parameter map; setting a parameter-value string clears "Use Defaults".
     *
     * @param aValue the new cell value ({@link Boolean} for column 6, a {@code key=value; ...}
     *     string for column 7)
     * @param rowIndex the row index
     * @param columnIndex the column index being edited
     */
    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        RowState row = rows.get(rowIndex);
        if (columnIndex == 6) {
            row.useDefaults = Boolean.TRUE.equals(aValue);
            if (row.useDefaults) {
                row.parameters.clear();
            }
        } else if (columnIndex == 7 && aValue != null) {
            row.useDefaults = false;
            row.parameters = parseDisplayValue(aValue.toString());
        }
        fireTableRowsUpdated(rowIndex, rowIndex);
    }

    /**
     * Returns the edited (or default, if unedited) parameter values for the test with the
     * given ID.
     *
     * @param testId the test's identifier
     * @return the parameter values currently set for that test, or an empty map if the test
     *     is not found or uses default parameters
     */
    public Map<String, String> editedParametersFor(String testId) {
        return settingsFor(testId).parameters();
    }

    /**
     * @param rowIndex the row index
     * @return the {@link BindingReview} backing the given row
     */
    public BindingReview reviewAt(int rowIndex) {
        return rows.get(rowIndex).review;
    }

    /**
     * @param rowIndex the row index
     * @return {@code true} if the row's review exposes a parameterized implementation method
     *     (i.e. its {@link org.filteredpush.bdq_workbench.model.ParameterizationCapability} is
     *     not {@code DEFAULT_ONLY}), making its "Use Defaults" and "Parameter Values" cells
     *     editable
     */
    public boolean supportsParameterEditing(int rowIndex) {
        return rows.get(rowIndex).supportsParameterEditing();
    }

    /**
     * Looks up the current (possibly edited) parameter settings for the test with the given ID.
     *
     * @param testId the test's identifier
     * @return the current {@link ParameterSettings} for that test, or a default
     *     ({@code useDefaults = true}, empty parameters) settings instance if the test is not
     *     found
     */
    public ParameterSettings settingsFor(String testId) {
        return rows.stream()
                .filter(row -> row.review.test().id().equals(testId))
                .findFirst()
                .map(RowState::settings)
                .orElse(new ParameterSettings(true, Map.of()));
    }

    /**
     * @return the current parameter settings for every row, keyed by test ID
     */
    public Map<String, ParameterSettings> parameterSettings() {
        Map<String, ParameterSettings> settings = new LinkedHashMap<>();
        rows.forEach(row -> settings.put(row.review.test().id(), row.settings()));
        return Map.copyOf(settings);
    }

    /**
     * Restores previously captured parameter settings onto the matching rows (by test ID),
     * firing a {@code TableRowsUpdated} event for each row that is updated. Rows whose test ID
     * has no entry in {@code settings} are left unchanged.
     *
     * @param settings parameter settings to apply, keyed by test ID; a null or empty map is a
     *     no-op
     */
    public void applyParameterSettings(Map<String, ParameterSettings> settings) {
        if (settings == null || settings.isEmpty()) {
            return;
        }
        for (int index = 0; index < rows.size(); index++) {
            RowState row = rows.get(index);
            ParameterSettings setting = settings.get(row.review.test().id());
            if (setting != null) {
                row.apply(setting);
                fireTableRowsUpdated(index, index);
            }
        }
    }

    /**
     * Updates the pre-/post-amendment preview columns from a preflight sample execution,
     * firing a {@code TableRowsUpdated} event for each row whose output actually changed.
     *
     * @param outputs preview output keyed by test ID; a null map is treated as empty, and rows
     *     with no entry fall back to empty pre-/post-amendment strings
     */
    public void applyExecutionOutputs(Map<String, PhaseExecutionOutput> outputs) {
        Map<String, PhaseExecutionOutput> safeOutputs = outputs == null ? Map.of() : outputs;
        for (int index = 0; index < rows.size(); index++) {
            RowState row = rows.get(index);
            PhaseExecutionOutput next = safeOutputs.getOrDefault(row.review.test().id(), new PhaseExecutionOutput("", ""));
            if (!next.preAmendment().equals(row.preAmendmentOutput)
                    || !next.postAmendment().equals(row.postAmendmentOutput)) {
                row.preAmendmentOutput = next.preAmendment();
                row.postAmendmentOutput = next.postAmendment();
                fireTableRowsUpdated(index, index);
            }
        }
    }

    /**
     * Preview output produced by a preflight sample execution of a single test.
     *
     * @param preAmendment display value of the term(s) before amendment
     * @param postAmendment display value of the term(s) after amendment
     */
    public record PhaseExecutionOutput(String preAmendment, String postAmendment) {
    }

    /**
     * A test's editable parameter configuration.
     *
     * @param useDefaults whether the test should be invoked with its default parameter values
     *     rather than {@code parameters}
     * @param parameters explicit parameter values to use when {@code useDefaults} is false;
     *     defensively copied to an immutable map (null is treated as empty)
     */
    public record ParameterSettings(boolean useDefaults, Map<String, String> parameters) {
        public ParameterSettings {
            parameters = Map.copyOf(parameters == null ? Map.of() : new LinkedHashMap<>(parameters));
        }
    }

    /**
     * Renders a parameter map as a sorted {@code key=value; key2=value2} display string.
     *
     * @param values the parameter values to render
     * @return the display string, or the empty string if {@code values} is empty
     */
    private static String toDisplayValue(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted()
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    /**
     * Parses a {@code key=value; key2=value2} display string back into a parameter map, the
     * inverse of {@link #toDisplayValue(Map)}. Entries without an {@code =} are treated as a
     * key with an empty value; blank segments are skipped.
     *
     * @param display the display string to parse
     * @return the parsed parameter values, in encounter order
     */
    private static Map<String, String> parseDisplayValue(String display) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : display.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                values.put(trimmed, "");
            } else {
                values.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
            }
        }
        return values;
    }

    /** Mutable per-row state: the underlying review plus its editable parameter/preview state. */
    private static final class RowState {
        private final BindingReview review;
        private boolean useDefaults;
        private Map<String, String> parameters;
        private String preAmendmentOutput;
        private String postAmendmentOutput;

        /**
         * Creates row state initialized from the review's own default parameter settings.
         *
         * @param review the binding review this row displays
         */
        private RowState(BindingReview review) {
            this.review = review;
            this.useDefaults = review.usingDefaultParameters();
            this.parameters = new LinkedHashMap<>(review.parameterValues());
            this.preAmendmentOutput = "";
            this.postAmendmentOutput = "";
        }

        /**
         * @return an immutable snapshot of this row's current parameter settings
         */
        private ParameterSettings settings() {
            return new ParameterSettings(useDefaults, Map.copyOf(parameters));
        }

        /**
         * Overwrites this row's parameter settings, clearing the parameter map when defaults
         * are selected.
         *
         * @param settings the settings to apply
         */
        private void apply(ParameterSettings settings) {
            this.useDefaults = settings.useDefaults();
            this.parameters = new LinkedHashMap<>(settings.parameters());
            if (this.useDefaults) {
                this.parameters.clear();
            }
        }

        /**
         * @return {@code true} if the underlying review's parameterization capability is not
         *     {@code DEFAULT_ONLY}
         */
        private boolean supportsParameterEditing() {
            return review.parameterizationCapability() != org.filteredpush.bdq_workbench.model.ParameterizationCapability.DEFAULT_ONLY;
        }
    }
}
