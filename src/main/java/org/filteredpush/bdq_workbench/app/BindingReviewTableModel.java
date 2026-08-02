package org.filteredpush.bdq_workbench.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.table.AbstractTableModel;
import org.filteredpush.bdq_workbench.model.BindingReview;

/** Table model for preflight binding review and parameter edits. */
public class BindingReviewTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            "Test Label/Id",
            "Type",
            "Implementation",
            "Binding",
            "Capability",
            "Chosen Method",
            "Use Defaults",
            "Parameter Values"
    };

    private final List<RowState> rows = new ArrayList<>();

    public BindingReviewTableModel(List<BindingReview> reviews) {
        reviews.forEach(review -> rows.add(new RowState(review)));
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == 6 ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return supportsParameterEditing(rowIndex) && (columnIndex == 6 || columnIndex == 7);
    }

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
            default -> "";
        };
    }

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

    public Map<String, String> editedParametersFor(String testId) {
        return settingsFor(testId).parameters();
    }

    public BindingReview reviewAt(int rowIndex) {
        return rows.get(rowIndex).review;
    }

    public boolean supportsParameterEditing(int rowIndex) {
        return rows.get(rowIndex).supportsParameterEditing();
    }

    public ParameterSettings settingsFor(String testId) {
        return rows.stream()
                .filter(row -> row.review.test().id().equals(testId))
                .findFirst()
                .map(RowState::settings)
                .orElse(new ParameterSettings(true, Map.of()));
    }

    public Map<String, ParameterSettings> parameterSettings() {
        Map<String, ParameterSettings> settings = new LinkedHashMap<>();
        rows.forEach(row -> settings.put(row.review.test().id(), row.settings()));
        return Map.copyOf(settings);
    }

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

    public record ParameterSettings(boolean useDefaults, Map<String, String> parameters) {
        public ParameterSettings {
            parameters = Map.copyOf(parameters == null ? Map.of() : new LinkedHashMap<>(parameters));
        }
    }

    private static String toDisplayValue(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted()
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

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

    private static final class RowState {
        private final BindingReview review;
        private boolean useDefaults;
        private Map<String, String> parameters;

        private RowState(BindingReview review) {
            this.review = review;
            this.useDefaults = review.usingDefaultParameters();
            this.parameters = new LinkedHashMap<>(review.parameterValues());
        }

        private ParameterSettings settings() {
            return new ParameterSettings(useDefaults, Map.copyOf(parameters));
        }

        private void apply(ParameterSettings settings) {
            this.useDefaults = settings.useDefaults();
            this.parameters = new LinkedHashMap<>(settings.parameters());
            if (this.useDefaults) {
                this.parameters.clear();
            }
        }

        private boolean supportsParameterEditing() {
            return review.parameterizationCapability() != org.filteredpush.bdq_workbench.model.ParameterizationCapability.DEFAULT_ONLY;
        }
    }
}
