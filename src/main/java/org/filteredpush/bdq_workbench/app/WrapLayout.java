/** WrapLayout.java
 *
 * A FlowLayout that reports the height it actually needs, so a row of buttons wraps instead of being clipped.
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

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A {@link FlowLayout} that reports the height its contents actually need at the width it is
 * given.
 *
 * <p>{@code FlowLayout} does wrap its components onto further rows, but it always reports a
 * preferred size one row tall. A button row in a {@code BorderLayout}'s {@code SOUTH} position is
 * therefore given only enough height for one row, and every button past the container's width is
 * silently clipped — the user cannot see that the button exists, let alone press it, until they
 * widen the window. Reporting the wrapped height instead lets the row grow to two rows and keeps
 * every button reachable at any window width.
 */
public class WrapLayout extends FlowLayout {
	private static final long serialVersionUID = 1L;

	/**
	 * Creates a wrapping layout with the given alignment and the default gaps.
	 *
	 * @param align one of {@link FlowLayout#LEFT}, {@link FlowLayout#CENTER} or
	 *     {@link FlowLayout#RIGHT}
	 */
	public WrapLayout(int align) {
		super(align);
	}

	/**
	 * Creates a wrapping layout with the given alignment and gaps.
	 *
	 * @param align one of {@link FlowLayout#LEFT}, {@link FlowLayout#CENTER} or
	 *     {@link FlowLayout#RIGHT}
	 * @param hgap horizontal gap between components
	 * @param vgap vertical gap between rows
	 */
	public WrapLayout(int align, int hgap, int vgap) {
		super(align, hgap, vgap);
	}

	/**
	 * Returns the preferred size, accounting for the rows the contents wrap onto.
	 *
	 * @param target the container being laid out
	 * @return the preferred size
	 */
	@Override
	public Dimension preferredLayoutSize(Container target) {
		return layoutSize(target, true);
	}

	/**
	 * Returns the minimum size, accounting for the rows the contents wrap onto.
	 *
	 * @param target the container being laid out
	 * @return the minimum size
	 */
	@Override
	public Dimension minimumLayoutSize(Container target) {
		Dimension minimum = layoutSize(target, false);
		minimum.width -= getHgap() + 1;
		return minimum;
	}

	/**
	 * Computes the size the container needs once its components have wrapped.
	 *
	 * <p>The container's current width is what the components wrap against. Before the first
	 * layout that width is zero, in which case everything is measured on a single row, which is
	 * the same answer plain {@code FlowLayout} gives and is corrected on the next validation.
	 *
	 * @param target the container being laid out
	 * @param preferred {@code true} to measure components at their preferred size, {@code false}
	 *     at their minimum size
	 * @return the size the container needs
	 */
	private Dimension layoutSize(Container target, boolean preferred) {
		synchronized (target.getTreeLock()) {
			int targetWidth = target.getSize().width;
			if (targetWidth == 0) {
				targetWidth = Integer.MAX_VALUE;
			}
			Insets insets = target.getInsets();
			int horizontalInsetsAndGap = insets.left + insets.right + getHgap() * 2;
			int maximumRowWidth = targetWidth - horizontalInsetsAndGap;

			Dimension size = new Dimension(0, 0);
			int rowWidth = 0;
			int rowHeight = 0;
			for (int index = 0; index < target.getComponentCount(); index++) {
				Component member = target.getComponent(index);
				if (!member.isVisible()) {
					continue;
				}
				Dimension memberSize = preferred ? member.getPreferredSize() : member.getMinimumSize();
				if (rowWidth != 0 && rowWidth + getHgap() + memberSize.width > maximumRowWidth) {
					addRow(size, rowWidth, rowHeight);
					rowWidth = 0;
					rowHeight = 0;
				}
				if (rowWidth != 0) {
					rowWidth += getHgap();
				}
				rowWidth += memberSize.width;
				rowHeight = Math.max(rowHeight, memberSize.height);
			}
			addRow(size, rowWidth, rowHeight);

			size.width += horizontalInsetsAndGap;
			size.height += insets.top + insets.bottom + getVgap() * 2;

			// Inside a scroll pane the viewport's own border would otherwise push the contents
			// one pixel wider than the viewport on every validation, oscillating the scroll bar.
			if (SwingUtilities.getAncestorOfClass(JScrollPane.class, target) != null && target.isValid()) {
				size.width -= getHgap() + 1;
			}
			return size;
		}
	}

	/**
	 * Folds one completed row into the running size.
	 *
	 * @param size the running size, updated in place
	 * @param rowWidth the completed row's width
	 * @param rowHeight the completed row's height
	 */
	private void addRow(Dimension size, int rowWidth, int rowHeight) {
		size.width = Math.max(size.width, rowWidth);
		if (size.height > 0) {
			size.height += getVgap();
		}
		size.height += rowHeight;
	}
}
