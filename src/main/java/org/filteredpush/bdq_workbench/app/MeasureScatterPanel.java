/** MeasureScatterPanel.java
 *
 * Plots each multi-record COUNT measure's pre-amendment against its post-amendment percentage.
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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import org.filteredpush.bdq_workbench.reporting.MeasureScatterChart;
import org.filteredpush.bdq_workbench.reporting.MultiRecordMeasureSummaries.Row;

/**
 * Shows a {@link MeasureScatterChart} in the workflow visualization, one point per multi-record
 * COUNT measure (pre- against post-amendment percentage); hovering a point names its measure(s).
 */
final class MeasureScatterPanel extends JPanel {
	private static final long serialVersionUID = 1L;

	private final transient List<Row> rows;

	/**
	 * @param rows the COUNT measures; those without both phases are not plotted
	 */
	MeasureScatterPanel(List<Row> rows) {
		this.rows = rows.stream().filter(Row::hasBothPhases).toList();
		setOpaque(true);
		setBackground(Color.WHITE);
		setPreferredSize(new Dimension(MeasureScatterChart.WIDTH, MeasureScatterChart.HEIGHT));
		setMaximumSize(getPreferredSize());
		setAlignmentX(LEFT_ALIGNMENT);
		ToolTipManager.sharedInstance().registerComponent(this);
	}

	@Override
	public String getToolTipText(MouseEvent event) {
		int reach = MeasureScatterChart.RADIUS + 2;
		StringBuilder text = new StringBuilder();
		for (Row row : rows) {
			int dx = event.getX() - MeasureScatterChart.px(row.pre().percent());
			int dy = event.getY() - MeasureScatterChart.py(row.post().percent());
			if (dx * dx + dy * dy <= reach * reach) {
				text.append(text.length() == 0 ? "<html>" : "<br>")
						.append(row.targetLabel())
						.append(": pre ").append(row.pre().count()).append('/').append(row.pre().total())
						.append(String.format(" (%.0f%%)", row.pre().percent()))
						.append(" \u2192 post ").append(row.post().count()).append('/').append(row.post().total())
						.append(String.format(" (%.0f%%)", row.post().percent()));
			}
		}
		return text.length() == 0 ? null : text.append("</html>").toString();
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		MeasureScatterChart.paint(graphics, rows);
	}
}
