/** InputViewDiagram.java
 *
 * Renders an inline SVG diagram of a run's input tables, their relationships, and the construction of the execution view.
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewMode;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;
import org.filteredpush.bdq_workbench.reporting.InputViewOverview.TableOverview;
import org.filteredpush.bdq_workbench.reporting.InputViewOverview.TableRole;

/**
 * Draws an {@link InputViewOverview} as an inline SVG diagram.
 *
 * <p>Input tables are stacked in one column, grain table first, then contributing tables, then
 * tables not included, then tables ignored for lacking test bindings. Discovered relationships are
 * drawn as arcs along the column's left side, and arrows run from the grain table and each
 * contributing table into an "execution view" box that states how the view was built. Ignored and
 * not-included tables are drawn muted with dashed borders and no arrow into the view. The layout
 * is deterministic and needs no script, so the report stays a single self-contained file.
 */
final class InputViewDiagram {

	private static final int TABLE_X = 150;
	private static final int TABLE_WIDTH = 260;
	private static final int TABLE_HEIGHT = 64;
	private static final int TABLE_GAP = 16;
	private static final int VIEW_X = 540;
	private static final int VIEW_WIDTH = 330;
	private static final int VIEW_LINE_HEIGHT = 18;
	private static final int VIEW_PADDING = 14;
	private static final int SVG_WIDTH = 880;
	private static final int TOP = 40;
	private static final int BOTTOM_PADDING = 16;
	private static final int MIN_ARC_BULGE = 24;
	private static final int MAX_ARC_BULGE = 130;
	private static final double ARC_BULGE_PER_PIXEL = 0.3;
	private static final int MAX_TABLE_NAME_CHARS = 26;
	private static final int MAX_VIEW_TEXT_CHARS = 46;
	private static final int TEXT_INSET = 10;
	private static final List<TableRole> ROLE_ORDER = List.of(
			TableRole.GRAIN, TableRole.CONTRIBUTING, TableRole.NOT_INCLUDED, TableRole.IGNORED_NO_BINDINGS);

	/**
	 * CSS rules the diagram's classes rely on, for inclusion in the report's style block.
	 */
	static final String CSS = ""
			+ "    .view-diagram { max-width: 880px; margin-top: 0.75rem; }\n"
			+ "    .view-diagram svg { width: 100%; height: auto; font-family: sans-serif; }\n"
			+ "    .vw-box rect { stroke-width: 1.5; }\n"
			+ "    .vw-box text { font-size: 12px; fill: #1f2328; }\n"
			+ "    .vw-box text.vw-name { font-size: 13px; font-weight: 600; }\n"
			+ "    .vw-box text.vw-meta { fill: #57606a; }\n"
			+ "    .vw-grain rect { fill: #ddf4ff; stroke: #0969da; }\n"
			+ "    .vw-contrib rect { fill: #dafbe1; stroke: #1a7f37; }\n"
			+ "    .vw-notincl rect { fill: #fff8c5; stroke: #9a6700; stroke-dasharray: 2 3; }\n"
			+ "    .vw-ignored rect { fill: #f6f8fa; stroke: #8c959f; stroke-dasharray: 5 3; }\n"
			+ "    .vw-ignored text, .vw-notincl text { fill: #57606a; }\n"
			+ "    .vw-view rect { fill: #fbefff; stroke: #8250df; stroke-width: 2; }\n"
			+ "    .vw-rel { fill: none; stroke: #57606a; stroke-width: 1.5; }\n"
			+ "    .vw-rel.inactive { stroke: #afb8c1; stroke-dasharray: 3 3; }\n"
			+ "    .vw-flow { fill: none; stroke: #8250df; stroke-width: 1.5; }\n"
			+ "    .vw-flow-label { font-size: 11px; fill: #8250df; }\n"
			+ "    .vw-caption { font-size: 12px; font-weight: 600; fill: #57606a; }\n"
			+ "    .view-legend { display: flex; flex-wrap: wrap; gap: 0.5rem 1.25rem; font-size: 0.85rem; color: #57606a; margin-top: 0.5rem; }\n"
			+ "    .view-legend span::before { content: ''; display: inline-block; width: 0.9rem; height: 0.9rem; margin-right: 0.35rem; vertical-align: -0.15rem; border: 1.5px solid; border-radius: 3px; }\n"
			+ "    .view-legend .lg-grain::before { background: #ddf4ff; border-color: #0969da; }\n"
			+ "    .view-legend .lg-contrib::before { background: #dafbe1; border-color: #1a7f37; }\n"
			+ "    .view-legend .lg-notincl::before { background: #fff8c5; border-color: #9a6700; border-style: dotted; }\n"
			+ "    .view-legend .lg-ignored::before { background: #f6f8fa; border-color: #8c959f; border-style: dashed; }\n"
			+ "    .view-legend .lg-view::before { background: #fbefff; border-color: #8250df; }\n";

	private InputViewDiagram() {
	}

	/**
	 * Renders the diagram and its legend as an HTML fragment.
	 *
	 * @param overview the input-view overview to draw
	 * @return the diagram markup, or {@code ""} when the overview has no tables
	 */
	static String render(InputViewOverview overview) {
		if (overview.tables().isEmpty()) {
			return "";
		}
		List<TableOverview> ordered = orderTables(overview.tables());
		Map<String, Integer> topByTable = new LinkedHashMap<>();
		for (int index = 0; index < ordered.size(); index++) {
			topByTable.put(key(ordered.get(index).name()), TOP + index * (TABLE_HEIGHT + TABLE_GAP));
		}
		int columnHeight = ordered.size() * (TABLE_HEIGHT + TABLE_GAP) - TABLE_GAP;
		List<String> viewLines = viewLines(overview);
		int viewHeight = viewLines.size() * VIEW_LINE_HEIGHT + 2 * VIEW_PADDING;
		int viewTop = TOP + Math.max(0, (columnHeight - viewHeight) / 2);
		int height = TOP + Math.max(columnHeight, viewHeight) + BOTTOM_PADDING;

		StringBuilder svg = new StringBuilder()
				.append("  <div class=\"view-diagram\">\n")
				.append("    <svg viewBox=\"0 0 ").append(SVG_WIDTH).append(' ').append(height)
				.append("\" role=\"img\" aria-label=\"")
				.append(escape(ariaLabel(overview)))
				.append("\" xmlns=\"http://www.w3.org/2000/svg\">\n")
				.append("      <defs><marker id=\"vw-arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" ")
				.append("markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#8250df\"/>")
				.append("</marker></defs>\n");
		appendCaption(svg, TABLE_X, "Input tables");
		appendCaption(svg, VIEW_X, "Execution view");
		appendRelationshipArcs(svg, overview, topByTable);
		appendFlows(svg, overview.description(), ordered, topByTable, viewTop, viewHeight);
		for (TableOverview table : ordered) {
			appendTableBox(svg, overview.description(), table, topByTable.get(key(table.name())));
		}
		appendViewBox(svg, viewLines, viewTop, viewHeight);
		svg.append("    </svg>\n")
				.append("  </div>\n")
				.append("  <div class=\"view-legend\">")
				.append("<span class=\"lg-grain\">grain table</span>")
				.append("<span class=\"lg-contrib\">joined into view</span>")
				.append("<span class=\"lg-notincl\">has bound terms, not included in view</span>")
				.append("<span class=\"lg-ignored\">ignored: no test bindings</span>")
				.append("<span class=\"lg-view\">execution view</span>")
				.append("</div>\n");
		return svg.toString();
	}

	/**
	 * Orders tables by role for drawing, keeping declaration order within a role.
	 *
	 * @param tables the tables to order
	 * @return the tables in drawing order
	 */
	private static List<TableOverview> orderTables(List<TableOverview> tables) {
		List<TableOverview> ordered = new ArrayList<>(tables);
		ordered.sort(Comparator.comparingInt(table -> ROLE_ORDER.indexOf(table.role())));
		return ordered;
	}

	/**
	 * Appends one column caption.
	 *
	 * @param svg the SVG being built
	 * @param x the caption's left edge
	 * @param text the caption text
	 */
	private static void appendCaption(StringBuilder svg, int x, String text) {
		svg.append("      <text class=\"vw-caption\" x=\"").append(x).append("\" y=\"").append(TOP - 14).append("\">")
				.append(escape(text))
				.append("</text>\n");
	}

	/**
	 * Appends one arc per related table pair along the left side of the table column.
	 *
	 * @param svg the SVG being built
	 * @param overview the overview supplying relationships and roles
	 * @param topByTable each drawn table's top edge, keyed by lower-cased name
	 */
	private static void appendRelationshipArcs(
			StringBuilder svg,
			InputViewOverview overview,
			Map<String, Integer> topByTable) {
		Map<String, TableRole> roleByTable = new LinkedHashMap<>();
		overview.tables().forEach(table -> roleByTable.put(key(table.name()), table.role()));
		String grain = key(overview.description().grainTable());
		Set<String> drawnPairs = new LinkedHashSet<>();
		for (RelationshipSchema relationship : overview.description().relationships()) {
			String from = key(relationship.fromTable());
			String to = key(relationship.toTable());
			if (from.equals(to) || !topByTable.containsKey(from) || !topByTable.containsKey(to)) {
				continue;
			}
			String pair = from.compareTo(to) < 0 ? from + "\u0000" + to : to + "\u0000" + from;
			if (!drawnPairs.add(pair)) {
				continue;
			}
			boolean active = (from.equals(grain) && roleByTable.get(to) == TableRole.CONTRIBUTING)
					|| (to.equals(grain) && roleByTable.get(from) == TableRole.CONTRIBUTING);
			int fromY = topByTable.get(from) + TABLE_HEIGHT / 2;
			int toY = topByTable.get(to) + TABLE_HEIGHT / 2;
			int bulge = (int) Math.min(MAX_ARC_BULGE, MIN_ARC_BULGE + Math.abs(fromY - toY) * ARC_BULGE_PER_PIXEL);
			svg.append("      <path class=\"vw-rel").append(active ? "" : " inactive").append("\" d=\"M ")
					.append(TABLE_X).append(' ').append(fromY)
					.append(" Q ").append(TABLE_X - bulge).append(' ').append((fromY + toY) / 2)
					.append(' ').append(TABLE_X).append(' ').append(toY)
					.append("\"><title>")
					.append(escape(relationship.fromTable() + "." + relationship.fromColumn() + " → "
							+ relationship.toTable() + "." + relationship.toColumn()))
					.append("</title></path>\n");
		}
	}

	/**
	 * Appends one arrow from each table the view read from into the execution-view box.
	 *
	 * @param svg the SVG being built
	 * @param description the recorded input description
	 * @param ordered the tables in drawing order
	 * @param topByTable each drawn table's top edge, keyed by lower-cased name
	 * @param viewTop the view box's top edge
	 * @param viewHeight the view box's height
	 */
	private static void appendFlows(
			StringBuilder svg,
			DatasetInputDescription description,
			List<TableOverview> ordered,
			Map<String, Integer> topByTable,
			int viewTop,
			int viewHeight) {
		List<TableOverview> sources = ordered.stream()
				.filter(table -> table.role() == TableRole.GRAIN || table.role() == TableRole.CONTRIBUTING)
				.toList();
		int startX = TABLE_X + TABLE_WIDTH;
		for (int index = 0; index < sources.size(); index++) {
			TableOverview table = sources.get(index);
			int startY = topByTable.get(key(table.name())) + TABLE_HEIGHT / 2;
			int endY = viewTop + viewHeight * (index + 1) / (sources.size() + 1);
			svg.append("      <path class=\"vw-flow\" marker-end=\"url(#vw-arrow)\" d=\"M ")
					.append(startX).append(' ').append(startY)
					.append(" C ").append(startX + (VIEW_X - startX) / 2).append(' ').append(startY)
					.append(' ').append(startX + (VIEW_X - startX) / 2).append(' ').append(endY)
					.append(' ').append(VIEW_X - 2).append(' ').append(endY)
					.append("\"/>\n");
			svg.append("      <text class=\"vw-flow-label\" x=\"").append(startX + 6)
					.append("\" y=\"").append(startY - 5).append("\">")
					.append(escape(flowLabel(description, table)))
					.append("</text>\n");
		}
	}

	/**
	 * Labels one arrow into the view with how that table's rows were used.
	 *
	 * @param description the recorded input description
	 * @param table the source table
	 * @return a short arrow label
	 */
	private static String flowLabel(DatasetInputDescription description, TableOverview table) {
		if (table.role() == TableRole.GRAIN) {
			return "1 record per row";
		}
		ViewRelation relation = table.relation();
		if (relation == null || relation.cardinalityPolicy() == null) {
			return "rows retained";
		}
		if (!relation.cardinalityPolicy().flattens()) {
			return "expand: 1 subject per row";
		}
		return relation.cardinalityPolicy().name().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	/**
	 * Appends one table box.
	 *
	 * @param svg the SVG being built
	 * @param description the recorded input description
	 * @param table the table to draw
	 * @param top the box's top edge
	 */
	private static void appendTableBox(
			StringBuilder svg,
			DatasetInputDescription description,
			TableOverview table,
			int top) {
		svg.append("      <g class=\"vw-box ").append(cssClass(table.role())).append("\">")
				.append("<title>")
				.append(escape(table.name() + " — " + table.role().label() + " — " + table.relationToGrain()))
				.append("</title>")
				.append("<rect x=\"").append(TABLE_X).append("\" y=\"").append(top)
				.append("\" width=\"").append(TABLE_WIDTH).append("\" height=\"").append(TABLE_HEIGHT)
				.append("\" rx=\"6\"/>");
		appendText(svg, "vw-name", TABLE_X + TEXT_INSET, top + 19, truncate(table.name(), MAX_TABLE_NAME_CHARS));
		if (!table.rowType().isBlank()) {
			svg.append("<text class=\"vw-meta\" x=\"").append(TABLE_X + TABLE_WIDTH - TEXT_INSET)
					.append("\" y=\"").append(top + 19).append("\" text-anchor=\"end\">")
					.append(escape(table.rowType()))
					.append("</text>");
		}
		appendText(svg, "vw-meta", TABLE_X + TEXT_INSET, top + 37,
				counted(table.recordCountLabel(), table.recordCount() == 1, "record") + " · "
						+ counted(Integer.toString(table.columnCount()), table.columnCount() == 1, "column"));
		appendText(svg, "", TABLE_X + TEXT_INSET, top + 54, boxDetail(description, table));
		svg.append("</g>\n");
	}

	/**
	 * Describes one table's part in the view, for the third line of its box.
	 *
	 * @param description the recorded input description
	 * @param table the table
	 * @return a short role detail
	 */
	private static String boxDetail(DatasetInputDescription description, TableOverview table) {
		ViewRelation relation = table.relation();
		return switch (table.role()) {
			case GRAIN -> "grain · " + table.suppliedTerms().size() + " bound term(s)";
			case CONTRIBUTING -> multiplicity(relation) + " · " + table.suppliedTerms().size() + " bound term(s)";
			case NOT_INCLUDED -> "not included in view";
			case IGNORED_NO_BINDINGS -> relation == null
					? "ignored: no test bindings"
					: "ignored: no test bindings (" + multiplicity(relation) + ")";
		};
	}

	/**
	 * Summarizes the related-row multiplicity observed for one relation.
	 *
	 * @param relation the relation, or {@code null}
	 * @return {@code "1 : n (max N)"} when multiplicity was seen, else {@code "1 : 0..1"}
	 */
	private static String multiplicity(ViewRelation relation) {
		if (relation == null) {
			return "related";
		}
		return relation.maxRowsPerCoreRecord() > 1
				? "1 : n (max " + relation.maxRowsPerCoreRecord() + ")"
				: "1 : 0..1";
	}

	/**
	 * Lists the lines drawn inside the execution-view box.
	 *
	 * @param overview the input-view overview
	 * @return the view box lines, the first being its title
	 */
	private static List<String> viewLines(InputViewOverview overview) {
		DatasetInputDescription description = overview.description();
		List<String> lines = new ArrayList<>();
		lines.add(overview.modeLabel());
		lines.add("grain: " + description.grainTable());
		lines.add(description.viewRecordCount() + " execution record(s)");
		lines.add(overview.includedTables().size() + " of " + overview.tables().size() + " input table(s) used");
		switch (description.viewMode()) {
			case FLATTENED -> lines.add("related rows collapsed per record");
			case STRUCTURED -> lines.add("related rows retained per record");
			default -> {
			}
		}
		if (!overview.ignoredTables().isEmpty()) {
			lines.add(overview.ignoredTables().size() + " ignored: no test bindings");
		}
		return lines;
	}

	/**
	 * Appends the execution-view box.
	 *
	 * @param svg the SVG being built
	 * @param lines the lines to draw, the first being its title
	 * @param top the box's top edge
	 * @param height the box's height
	 */
	private static void appendViewBox(StringBuilder svg, List<String> lines, int top, int height) {
		svg.append("      <g class=\"vw-box vw-view\"><rect x=\"").append(VIEW_X).append("\" y=\"").append(top)
				.append("\" width=\"").append(VIEW_WIDTH).append("\" height=\"").append(height)
				.append("\" rx=\"8\"/>");
		for (int index = 0; index < lines.size(); index++) {
			appendText(svg, index == 0 ? "vw-name" : "",
					VIEW_X + VIEW_PADDING,
					top + VIEW_PADDING + (index + 1) * VIEW_LINE_HEIGHT - 5,
					truncate(lines.get(index), MAX_VIEW_TEXT_CHARS));
		}
		svg.append("</g>\n");
	}

	/**
	 * Appends one SVG text element.
	 *
	 * @param svg the SVG being built
	 * @param cssClass the text's CSS class, or {@code ""}
	 * @param x the text's left edge
	 * @param y the text's baseline
	 * @param text the raw text
	 */
	private static void appendText(StringBuilder svg, String cssClass, int x, int y, String text) {
		svg.append("<text");
		if (!cssClass.isEmpty()) {
			svg.append(" class=\"").append(cssClass).append('"');
		}
		svg.append(" x=\"").append(x).append("\" y=\"").append(y).append("\">")
				.append(escape(text))
				.append("</text>");
	}

	/**
	 * Builds the diagram's accessible description.
	 *
	 * @param overview the input-view overview
	 * @return a one-sentence description of the diagram
	 */
	private static String ariaLabel(InputViewOverview overview) {
		return overview.modeLabel() + " over grain table " + overview.description().grainTable() + ": "
				+ overview.includedTables().size() + " of " + overview.tables().size() + " input tables used, "
				+ overview.ignoredTables().size() + " ignored for lacking test bindings";
	}

	/**
	 * Maps a table role to its CSS class.
	 *
	 * @param role the table role
	 * @return the CSS class name
	 */
	private static String cssClass(TableRole role) {
		return switch (role) {
			case GRAIN -> "vw-grain";
			case CONTRIBUTING -> "vw-contrib";
			case NOT_INCLUDED -> "vw-notincl";
			case IGNORED_NO_BINDINGS -> "vw-ignored";
		};
	}

	/**
	 * Renders a count with a singular or plural noun.
	 *
	 * @param count the rendered count
	 * @param singular whether the count is exactly one
	 * @param noun the singular noun
	 * @return the count followed by the noun, pluralized with {@code s} unless singular
	 */
	private static String counted(String count, boolean singular, String noun) {
		return count + " " + noun + (singular ? "" : "s");
	}

	/**
	 * Truncates text to a maximum length with an ellipsis.
	 *
	 * @param text the raw text
	 * @param maxChars the maximum number of characters to keep
	 * @return the text, truncated when longer than {@code maxChars}
	 */
	private static String truncate(String text, int maxChars) {
		return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + "…";
	}

	/**
	 * Normalizes a table name for lookups.
	 *
	 * @param name the table name
	 * @return the lower-cased name
	 */
	private static String key(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT);
	}

	/**
	 * Escapes text for SVG element content and double-quoted attribute values.
	 *
	 * @param raw the raw text
	 * @return the escaped text
	 */
	private static String escape(String raw) {
		return (raw == null ? "" : raw)
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;");
	}
}
