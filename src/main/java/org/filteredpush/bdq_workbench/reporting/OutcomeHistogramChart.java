/** OutcomeHistogramChart.java
 *
 * Draws a stacked histogram of the single-record outcome measures over the records assessed, for the GUI and the reports.
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
package org.filteredpush.bdq_workbench.reporting;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import org.filteredpush.bdq_workbench.model.SingleRecordOutcomeMeasureSpec.Tally;
import org.filteredpush.bdq_workbench.reporting.SingleRecordOutcomeHistograms.Histogram;
import org.filteredpush.bdq_workbench.reporting.SingleRecordOutcomeHistograms.Series;

/**
 * Draws a {@link Histogram} as a stacked histogram: x is a measure's value, 0 to the number of
 * tests of the counted type in the use case; y is the number of records, with one stacked segment
 * per measure. Uses only Java2D, so it also works headless, for the reports.
 */
public final class OutcomeHistogramChart {
	/** Preferred width. */
	public static final int WIDTH = 640;
	/** Preferred height. */
	public static final int HEIGHT = 220;
	/** Left margin, before the first bin. */
	public static final int LEFT = 48;
	private static final int RIGHT = 12;
	/** Top margin, above the plot. */
	public static final int TOP = 26;
	/** Bottom margin, below the plot. */
	public static final int BOTTOM = 40;

	private OutcomeHistogramChart() {
	}

	/**
	 * @param tally a measure
	 * @return the color of its segments
	 */
	public static Color colorFor(Tally tally) {
		return switch (tally) {
			case COMPLIANT -> Color.decode("#2e7d32");
			case NOT_COMPLIANT -> Color.decode("#c62828");
			case PREREQUISITES_NOT_MET -> Color.decode("#ef6c00");
			case AMENDMENTS_PROPOSED -> Color.decode("#1565c0");
		};
	}

	/**
	 * @param tally a measure
	 * @return its short legend label
	 */
	public static String legendLabel(Tally tally) {
		return switch (tally) {
			case COMPLIANT -> "COMPLIANT";
			case NOT_COMPLIANT -> "NOT_COMPLIANT";
			case PREREQUISITES_NOT_MET -> "PREREQUISITES_NOT_MET";
			case AMENDMENTS_PROPOSED -> "AMENDED or FILLED_IN";
		};
	}

	/**
	 * @param width the chart's width
	 * @param histogram the histogram
	 * @return the width of one bin
	 */
	public static double binWidth(int width, Histogram histogram) {
		return (double) Math.max(1, width - LEFT - RIGHT) / (histogram.maxValue() + 1);
	}

	/**
	 * Draws the chart.
	 *
	 * @param graphics where to draw
	 * @param chartWidth the chart's width
	 * @param chartHeight the chart's height
	 * @param histogram the histogram
	 */
	public static void paint(Graphics graphics, int chartWidth, int chartHeight, Histogram histogram) {
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			FontMetrics metrics = g.getFontMetrics();
			int plotHeight = Math.max(1, chartHeight - TOP - BOTTOM);
			int baseline = TOP + plotHeight;
			int maxCount = Math.max(1, histogram.maxStackedCount());
			double binWidth = binWidth(chartWidth, histogram);

			// Legend
			int legendX = LEFT;
			for (Series series : histogram.series()) {
				g.setColor(colorFor(series.tally()));
				g.fillRect(legendX, 8, 10, 10);
				g.setColor(Color.DARK_GRAY);
				String label = legendLabel(series.tally()) + " (" + series.total() + ")";
				g.drawString(label, legendX + 14, 17);
				legendX += 14 + metrics.stringWidth(label) + 16;
			}

			// Bars, one stacked segment per measure
			for (int value = 0; value <= histogram.maxValue(); value++) {
				int x = LEFT + (int) Math.round(value * binWidth) + 1;
				int barWidth = Math.max(1, (int) Math.round(binWidth) - 2);
				int y = baseline;
				for (Series series : histogram.series()) {
					int count = series.counts()[value];
					if (count == 0) {
						continue;
					}
					int barHeight = Math.max(1, (int) Math.round((double) count * plotHeight / maxCount));
					g.setColor(colorFor(series.tally()));
					g.fillRect(x, y - barHeight, barWidth, barHeight);
					y -= barHeight;
				}
			}

			// Axes
			g.setColor(Color.DARK_GRAY);
			g.drawLine(LEFT, baseline, chartWidth - RIGHT, baseline);
			g.drawLine(LEFT, TOP, LEFT, baseline);
			String maxLabel = Integer.toString(maxCount);
			g.drawString(maxLabel, LEFT - 6 - metrics.stringWidth(maxLabel), TOP + metrics.getAscent() / 2);
			g.drawString("0", LEFT - 6 - metrics.stringWidth("0"), baseline);
			int labelStep = Math.max(1, (int) Math.ceil(metrics.stringWidth("000") / binWidth));
			for (int value = 0; value <= histogram.maxValue(); value += labelStep) {
				String label = Integer.toString(value);
				int center = LEFT + (int) Math.round((value + 0.5) * binWidth);
				g.drawString(label, center - metrics.stringWidth(label) / 2, baseline + metrics.getHeight());
			}
			String axis = "number of " + histogram.countedType() + " tests with the outcome (of "
					+ histogram.testsInUseCase() + " in the use case)"
					+ (histogram.notBinned() > 0 ? "; " + histogram.notBinned() + " records without a count (ERROR)" : "");
			g.drawString(axis, LEFT, baseline + 2 * metrics.getHeight() + 2);
			Graphics2D rotated = (Graphics2D) g.create();
			try {
				rotated.rotate(-Math.PI / 2);
				rotated.drawString("records", -(TOP + plotHeight / 2 + metrics.stringWidth("records") / 2), 14);
			} finally {
				rotated.dispose();
			}
		} finally {
			g.dispose();
		}
	}
}
