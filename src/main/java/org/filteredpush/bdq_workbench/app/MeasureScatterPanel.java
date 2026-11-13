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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import org.filteredpush.bdq_workbench.reporting.MultiRecordMeasureSummaries.Row;

/**
 * A scatter plot with one point per multi-record COUNT measure: x is the percentage of records with
 * the counted outcome before amendment, y after. Points above the diagonal improved after amendment.
 * Points are colored by data quality dimension; hovering a point names its measure(s).
 */
final class MeasureScatterPanel extends JPanel {
	private static final long serialVersionUID = 1L;
	private static final int LEFT = 44;
	private static final int TOP = 10;
	private static final int PLOT = 300;
	private static final int LEGEND = 150;
	private static final int RADIUS = 4;

	private final transient List<Row> rows;

	/**
	 * @param rows the COUNT measures; those without both phases are not plotted
	 */
	MeasureScatterPanel(List<Row> rows) {
		this.rows = rows.stream().filter(Row::hasBothPhases).toList();
		setOpaque(true);
		setBackground(Color.WHITE);
		setPreferredSize(new Dimension(LEFT + PLOT + 24 + LEGEND, TOP + PLOT + 40));
		setMaximumSize(getPreferredSize());
		setAlignmentX(LEFT_ALIGNMENT);
		ToolTipManager.sharedInstance().registerComponent(this);
	}

	private static int px(double percent) {
		return LEFT + (int) Math.round(percent / 100d * PLOT);
	}

	private static int py(double percent) {
		return TOP + PLOT - (int) Math.round(percent / 100d * PLOT);
	}

	@Override
	public String getToolTipText(MouseEvent event) {
		StringBuilder text = new StringBuilder();
		for (Row row : rows) {
			int dx = event.getX() - px(row.pre().percent());
			int dy = event.getY() - py(row.post().percent());
			if (dx * dx + dy * dy <= (RADIUS + 2) * (RADIUS + 2)) {
				text.append(text.length() == 0 ? "<html>" : "<br>")
						.append(row.targetLabel())
						.append(": pre ").append(row.pre().count()).append('/').append(row.pre().total())
						.append(String.format(" (%.0f%%)", row.pre().percent()))
						.append(" → post ").append(row.post().count()).append('/').append(row.post().total())
						.append(String.format(" (%.0f%%)", row.post().percent()));
			}
		}
		return text.length() == 0 ? null : text.append("</html>").toString();
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			FontMetrics metrics = g.getFontMetrics();
			// Grid and axes
			g.setColor(new Color(0xEEEEEE));
			for (int percent = 25; percent <= 100; percent += 25) {
				g.drawLine(px(percent), TOP, px(percent), TOP + PLOT);
				g.drawLine(LEFT, py(percent), LEFT + PLOT, py(percent));
			}
			g.setColor(Color.GRAY);
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 4f}, 0f));
			g.drawLine(px(0), py(0), px(100), py(100));
			g.setStroke(new BasicStroke(1f));
			g.setColor(Color.DARK_GRAY);
			g.drawRect(LEFT, TOP, PLOT, PLOT);
			for (int percent = 0; percent <= 100; percent += 25) {
				String label = percent + "%";
				g.drawString(label, px(percent) - metrics.stringWidth(label) / 2, TOP + PLOT + metrics.getHeight());
				g.drawString(label, LEFT - 4 - metrics.stringWidth(label), py(percent) + metrics.getAscent() / 2 - 1);
			}
			g.drawString("pre-amendment", LEFT + PLOT / 2 - metrics.stringWidth("pre-amendment") / 2,
					TOP + PLOT + 2 * metrics.getHeight() + 2);
			Graphics2D rotated = (Graphics2D) g.create();
			try {
				rotated.rotate(-Math.PI / 2);
				rotated.drawString("post-amendment", -(TOP + PLOT / 2 + metrics.stringWidth("post-amendment") / 2), 10);
			} finally {
				rotated.dispose();
			}
			// Points
			Set<String> dimensions = new LinkedHashSet<>();
			for (Row row : rows) {
				dimensions.add(row.dimension());
				int x = px(row.pre().percent());
				int y = py(row.post().percent());
				Color color = DimensionColors.colorFor(row.dimension());
				g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 170));
				g.fillOval(x - RADIUS, y - RADIUS, 2 * RADIUS, 2 * RADIUS);
				g.setColor(color.darker());
				g.drawOval(x - RADIUS, y - RADIUS, 2 * RADIUS, 2 * RADIUS);
			}
			// Legend
			int legendX = LEFT + PLOT + 24;
			int legendY = TOP + 12;
			g.setColor(Color.DARK_GRAY);
			g.drawString("Dimension", legendX, legendY);
			for (String dimension : orderedDimensions(dimensions)) {
				legendY += metrics.getHeight() + 2;
				g.setColor(DimensionColors.colorFor(dimension));
				g.fillOval(legendX, legendY - 9, 9, 9);
				g.setColor(Color.DARK_GRAY);
				g.drawString(DimensionColors.labelFor(dimension), legendX + 14, legendY);
			}
			legendY += 2 * metrics.getHeight();
			g.setColor(Color.GRAY);
			g.drawString("above the diagonal:", legendX, legendY);
			g.drawString("improved by amendment", legendX, legendY + metrics.getHeight());
		} finally {
			g.dispose();
		}
	}

	private static List<String> orderedDimensions(Set<String> present) {
		List<String> ordered = new java.util.ArrayList<>();
		DimensionColors.KNOWN.stream().filter(present::contains).forEach(ordered::add);
		present.stream().filter(dimension -> !DimensionColors.KNOWN.contains(dimension)).forEach(ordered::add);
		return ordered;
	}
}
