/** MeasureDumbbellPanel.java
 *
 * Draws one compact pre/post row per multi-record COUNT measure, greatest improvement first.
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
import java.util.List;
import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import org.filteredpush.bdq_workbench.reporting.DimensionColors;
import org.filteredpush.bdq_workbench.reporting.MultiRecordMeasureSummaries.Row;

/**
 * One row per multi-record COUNT measure, in the order given (greatest improvement first): a
 * dimension marker, the validation it counts, a dumbbell on a shared 0-100% axis (pre-amendment a
 * hollow ring, post-amendment a filled dot, joined by a bar; unchanged measures muted), and the
 * paired QA measure's result before and after amendment. Hovering a row gives its counts.
 */
final class MeasureDumbbellPanel extends JPanel {
	private static final long serialVersionUID = 1L;
	private static final int ROW = 18;
	private static final int TOP = 20;
	private static final int LABEL = 300;
	private static final int AXIS = 260;
	private static final int QA = 220;
	private static final int RADIUS = 4;

	private final transient List<Row> rows;

	/**
	 * @param rows the COUNT measures, in display order
	 */
	MeasureDumbbellPanel(List<Row> rows) {
		this.rows = rows;
		setOpaque(true);
		setBackground(Color.WHITE);
		setPreferredSize(new Dimension(LABEL + AXIS + QA + 40, TOP + rows.size() * ROW + 6));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
		setAlignmentX(LEFT_ALIGNMENT);
		ToolTipManager.sharedInstance().registerComponent(this);
	}

	private static int ax(double percent) {
		return LABEL + 16 + (int) Math.round(percent / 100d * AXIS);
	}

	@Override
	public String getToolTipText(MouseEvent event) {
		int index = (event.getY() - TOP) / ROW;
		if (event.getY() < TOP || index < 0 || index >= rows.size()) {
			return null;
		}
		Row row = rows.get(index);
		StringBuilder text = new StringBuilder("<html>").append(row.label())
				.append("<br>Dimension: ").append(DimensionColors.labelFor(row.dimension()))
				.append("<br>Pre-amendment: ").append(phaseText(row.pre()))
				.append("<br>Post-amendment: ").append(phaseText(row.post()));
		if (row.qaPre() != null || row.qaPost() != null) {
			text.append("<br>QA: ").append(qaText(row));
		}
		return text.append("</html>").toString();
	}

	private static String phaseText(org.filteredpush.bdq_workbench.reporting.MultiRecordMeasureSummaries.PhaseValue value) {
		return value == null ? "not run"
				: value.count() + " of " + value.total() + " records (" + String.format("%.1f%%", value.percent()) + ")";
	}

	private static String qaText(Row row) {
		return (row.qaPre() == null ? "–" : row.qaPre()) + " → " + (row.qaPost() == null ? "–" : row.qaPost());
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			FontMetrics metrics = g.getFontMetrics();
			// Axis header
			g.setColor(Color.GRAY);
			for (int percent = 0; percent <= 100; percent += 25) {
				String label = percent + "%";
				g.drawString(label, ax(percent) - metrics.stringWidth(label) / 2, TOP - 6);
			}
			g.drawString("QA result (pre → post)", LABEL + AXIS + 40, TOP - 6);
			for (int index = 0; index < rows.size(); index++) {
				Row row = rows.get(index);
				int y = TOP + index * ROW + ROW / 2;
				boolean muted = !row.hasBothPhases() || Math.abs(row.change()) < 1e-9;
				Color ink = muted ? Color.GRAY : Color.DARK_GRAY;
				if (index % 2 == 1) {
					g.setColor(new Color(0xF7F7F7));
					g.fillRect(0, y - ROW / 2, getWidth(), ROW);
				}
				// Dimension marker and label
				g.setColor(DimensionColors.colorFor(row.dimension()));
				g.fillRect(4, y - 5, 8, 10);
				g.setColor(ink);
				g.drawString(fit(row.targetLabel(), metrics, LABEL - 20), 18, y + metrics.getAscent() / 2 - 1);
				// Track
				g.setColor(new Color(0xE0E0E0));
				g.drawLine(ax(0), y, ax(100), y);
				// Dumbbell
				Color color = muted ? Color.GRAY : DimensionColors.colorFor(row.dimension());
				if (row.hasBothPhases()) {
					g.setColor(color);
					g.setStroke(new BasicStroke(3f));
					g.drawLine(ax(row.pre().percent()), y, ax(row.post().percent()), y);
					g.setStroke(new BasicStroke(1f));
				}
				if (row.post() != null) {
					g.setColor(color);
					g.fillOval(ax(row.post().percent()) - RADIUS, y - RADIUS, 2 * RADIUS, 2 * RADIUS);
				}
				if (row.pre() != null) {
					g.setColor(Color.WHITE);
					g.fillOval(ax(row.pre().percent()) - RADIUS, y - RADIUS, 2 * RADIUS, 2 * RADIUS);
					g.setColor(color);
					g.drawOval(ax(row.pre().percent()) - RADIUS, y - RADIUS, 2 * RADIUS, 2 * RADIUS);
				}
				// QA result
				if (row.qaPre() != null || row.qaPost() != null) {
					g.setColor(ink);
					g.drawString(qaText(row), LABEL + AXIS + 40, y + metrics.getAscent() / 2 - 1);
				}
			}
		} finally {
			g.dispose();
		}
	}

	private static String fit(String text, FontMetrics metrics, int width) {
		if (metrics.stringWidth(text) <= width) {
			return text;
		}
		String shortened = text;
		while (shortened.length() > 1 && metrics.stringWidth(shortened + "…") > width) {
			shortened = shortened.substring(0, shortened.length() - 1);
		}
		return shortened + "…";
	}
}
