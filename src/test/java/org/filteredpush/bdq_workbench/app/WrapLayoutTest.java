package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link WrapLayout} reports the height its contents need once wrapped, so a button
 * row is never clipped down to the buttons that happen to fit on one line.
 */
class WrapLayoutTest {

	/** Width of each stand-in for a button, chosen so row arithmetic is easy to check. */
	private static final int MEMBER_WIDTH = 100;

	/** Height of each stand-in for a button. */
	private static final int MEMBER_HEIGHT = 30;

	/** Gap between members and between rows. */
	private static final int GAP = 10;

	@Test
	void reportsAdditionalRowsWhenMembersDoNotFitOnOneLine() {
		JPanel panel = panelWith(5);
		// Room for three members and their gaps, plus the layout's own edge gaps.
		panel.setSize(new Dimension(3 * MEMBER_WIDTH + 2 * GAP + 2 * GAP, 200));

		Dimension preferred = panel.getLayout().preferredLayoutSize(panel);

		assertThat(preferred.height).isEqualTo(2 * MEMBER_HEIGHT + 3 * GAP);
	}

	@Test
	void reportsOneRowWhenEverythingFits() {
		JPanel panel = panelWith(5);
		panel.setSize(new Dimension(2000, 200));

		Dimension preferred = panel.getLayout().preferredLayoutSize(panel);

		assertThat(preferred.height).isEqualTo(MEMBER_HEIGHT + 2 * GAP);
	}

	@Test
	void keepsEveryMemberReachableWhereFlowLayoutWouldClipThem() {
		int narrowWidth = 2 * MEMBER_WIDTH + 3 * GAP;
		JPanel wrapping = panelWith(6);
		wrapping.setSize(new Dimension(narrowWidth, 200));
		JPanel plain = panelWith(6);
		plain.setLayout(new FlowLayout(FlowLayout.RIGHT, GAP, GAP));
		plain.setSize(new Dimension(narrowWidth, 200));

		int wrappingHeight = wrapping.getLayout().preferredLayoutSize(wrapping).height;
		int plainHeight = plain.getLayout().preferredLayoutSize(plain).height;

		// Plain FlowLayout asks for one row's height however many rows it will actually use, so
		// a BorderLayout.SOUTH slot sized from it hides the overflow.
		assertThat(plainHeight).isEqualTo(MEMBER_HEIGHT + 2 * GAP);
		assertThat(wrappingHeight).isGreaterThan(plainHeight);

		wrapping.doLayout();
		assertThat(wrapping.getComponents())
				.allSatisfy(member -> assertThat(member.getY() + member.getHeight())
						.isLessThanOrEqualTo(wrappingHeight));
	}

	@Test
	void anUnsizedContainerFallsBackToASingleRow() {
		JPanel panel = panelWith(4);

		Dimension preferred = panel.getLayout().preferredLayoutSize(panel);

		assertThat(preferred.height).isEqualTo(MEMBER_HEIGHT + 2 * GAP);
		assertThat(preferred.width).isEqualTo(4 * MEMBER_WIDTH + 3 * GAP + 2 * GAP);
	}

	@Test
	void theMonitorCardButtonRowFitsTheDefaultWindowAndStaysVisibleWhenNarrower() throws Exception {
		// The labels the monitor card uses, widest variant of each ("Start Available Tests" is
		// the state the button is in whenever some tests could not be bound).
		JPanel row = new JPanel(new WrapLayout(FlowLayout.RIGHT));
		for (String label : new String[] {"Load Parameters...", "Save Parameters...",
				"Show Workflow Visualization", "Back to Select Inputs", "Start Available Tests", "Quit"}) {
			row.add(new JButton(label));
		}
		int oneRowHeight = row.getLayout().preferredLayoutSize(row).height;

		row.setSize(new Dimension(usableWidthFor(frameConstant("PREFERRED_FRAME_WIDTH")), 200));
		assertThat(row.getLayout().preferredLayoutSize(row).height)
				.describedAs("button row should need only one line at the default window width")
				.isEqualTo(oneRowHeight);

		int narrow = usableWidthFor(frameConstant("MINIMUM_FRAME_WIDTH"));
		row.setSize(new Dimension(narrow, 200));
		int wrappedHeight = row.getLayout().preferredLayoutSize(row).height;
		assertThat(wrappedHeight)
				.describedAs("button row should ask for more height once it wraps")
				.isGreaterThan(oneRowHeight);

		row.setSize(new Dimension(narrow, wrappedHeight));
		row.doLayout();
		assertThat(row.getComponents()).allSatisfy(button -> {
			assertThat(button.getX()).isGreaterThanOrEqualTo(0);
			assertThat(button.getX() + button.getWidth()).isLessThanOrEqualTo(narrow);
			assertThat(button.getY() + button.getHeight()).isLessThanOrEqualTo(wrappedHeight);
		});
	}

	/**
	 * Reads one of the main window's private size constants.
	 *
	 * @param name the constant's field name
	 * @return its value
	 * @throws Exception if the field is missing or unreadable
	 */
	private static int frameConstant(String name) throws Exception {
		java.lang.reflect.Field field = BdqWorkbenchGui.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(null);
	}

	/**
	 * Approximates the width a button row actually gets inside the main window, allowing for the
	 * frame's own insets and the root panel's border.
	 *
	 * @param frameWidth the window's outer width
	 * @return the width available to the button row
	 */
	private static int usableWidthFor(int frameWidth) {
		return frameWidth - 60;
	}

	/**
	 * Builds a panel of fixed-size members laid out by {@link WrapLayout}.
	 *
	 * @param memberCount how many members to add
	 * @return the panel, not yet sized
	 */
	private JPanel panelWith(int memberCount) {
		JPanel panel = new JPanel(new WrapLayout(FlowLayout.RIGHT, GAP, GAP));
		for (int index = 0; index < memberCount; index++) {
			JPanel member = new JPanel();
			member.setPreferredSize(new Dimension(MEMBER_WIDTH, MEMBER_HEIGHT));
			member.setMinimumSize(new Dimension(MEMBER_WIDTH, MEMBER_HEIGHT));
			panel.add(member);
		}
		return panel;
	}
}
