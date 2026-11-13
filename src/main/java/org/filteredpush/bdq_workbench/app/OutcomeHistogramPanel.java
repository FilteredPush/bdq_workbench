/** OutcomeHistogramPanel.java
 *
 * Draws a stacked histogram of the single-record outcome measures over the records assessed.
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
import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import org.filteredpush.bdq_workbench.reporting.OutcomeHistogramChart;
import org.filteredpush.bdq_workbench.reporting.SingleRecordOutcomeHistograms.Histogram;
import org.filteredpush.bdq_workbench.reporting.SingleRecordOutcomeHistograms.Series;

/**
 * Shows an {@link OutcomeHistogramChart} in the workflow visualization: x is a measure's value, 0
 * to the number of tests of the counted type in the use case; y is the number of records, with one
 * stacked segment per measure. Hovering a bar shows each measure's record count at that value.
 */
final class OutcomeHistogramPanel extends JPanel {
	private static final long serialVersionUID = 1L;

	private final transient Histogram histogram;

	/**
	 * @param histogram the histogram to draw
	 */
	OutcomeHistogramPanel(Histogram histogram) {
		this.histogram = histogram;
		setOpaque(true);
		setBackground(Color.WHITE);
		setPreferredSize(new Dimension(OutcomeHistogramChart.WIDTH, OutcomeHistogramChart.HEIGHT));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, OutcomeHistogramChart.HEIGHT));
		setAlignmentX(LEFT_ALIGNMENT);
		ToolTipManager.sharedInstance().registerComponent(this);
	}

	@Override
	public String getToolTipText(MouseEvent event) {
		int x = event.getX() - OutcomeHistogramChart.LEFT;
		if (x < 0 || event.getY() < OutcomeHistogramChart.TOP || event.getY() > getHeight() - OutcomeHistogramChart.BOTTOM) {
			return null;
		}
		int value = (int) (x / OutcomeHistogramChart.binWidth(getWidth(), histogram));
		if (value < 0 || value > histogram.maxValue()) {
			return null;
		}
		StringBuilder text = new StringBuilder("<html>Value " + value + " of " + histogram.testsInUseCase() + " "
				+ histogram.countedType() + " tests in the use case");
		for (Series series : histogram.series()) {
			text.append("<br>").append(OutcomeHistogramChart.legendLabel(series.tally())).append(": ")
					.append(series.counts()[value]).append(" records");
		}
		return text.append("</html>").toString();
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		OutcomeHistogramChart.paint(graphics, getWidth(), getHeight(), histogram);
	}
}
