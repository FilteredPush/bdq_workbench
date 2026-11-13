/** ChartImages.java
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
package org.filteredpush.bdq_workbench.reporting;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/** Renders the overview charts to PNG images for the reports, headless. */
public final class ChartImages {
	/** Pixel density of the images: drawn at twice their display size, so they stay sharp. */
	static final int SCALE = 2;

	private ChartImages() {
	}

	/**
	 * Draws a chart onto a white image and returns it as a PNG {@code data:} URI.
	 *
	 * @param width the chart's display width
	 * @param height the chart's display height
	 * @param painter draws the chart in display coordinates
	 * @return a {@code data:image/png;base64,...} URI
	 */
	public static String pngDataUri(int width, int height, Consumer<Graphics2D> painter) {
		BufferedImage image = new BufferedImage(width * SCALE, height * SCALE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.scale(SCALE, SCALE);
			painter.accept(g);
		} finally {
			g.dispose();
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, "png", bytes);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
	}
}
