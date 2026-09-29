package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.junit.jupiter.api.Test;

/**
 * Tests the interactive console prompt for undecided join policies.
 */
class ConsoleJoinPolicyResolverTest {

	@Test
	void asksForEachTableAndAcceptsNamesOrInitials() {
		StringWriter prompts = new StringWriter();
		ConsoleJoinPolicyResolver resolver = new ConsoleJoinPolicyResolver(
				new BufferedReader(new StringReader("sometimes\ne\nfirst_row\n")), new PrintWriter(prompts));

		var answers = resolver.resolve("occurrence", List.of(relation("identification", 3), relation("multimedia", 2)));

		assertThat(answers).containsEntry("identification", DatasetViewCardinalityPolicy.EXPAND)
				.containsEntry("multimedia", DatasetViewCardinalityPolicy.FIRST_ROW);
		assertThat(prompts.toString())
				.contains("identification (up to 3 rows per occurrence record; 1 records have more than one)")
				.contains("Unrecognized choice: sometimes")
				.contains("--join-policy identification=EXPAND --join-policy multimedia=FIRST_ROW");
	}

	@Test
	void blankAnswerOrEndOfInputLeavesTableUndecided() {
		ConsoleJoinPolicyResolver resolver = new ConsoleJoinPolicyResolver(
				new BufferedReader(new StringReader("\n")), new PrintWriter(new StringWriter()));

		assertThat(resolver.resolve("occurrence", List.of(relation("identification", 2), relation("media", 2))))
				.isEmpty();
	}

	@Test
	void parseRecognizesEveryPolicy() {
		assertThat(ConsoleJoinPolicyResolver.parse(" Aggregate ")).isEqualTo(DatasetViewCardinalityPolicy.AGGREGATE);
		assertThat(ConsoleJoinPolicyResolver.parse("r")).isEqualTo(DatasetViewCardinalityPolicy.REJECT);
		assertThat(ConsoleJoinPolicyResolver.parse("first row")).isEqualTo(DatasetViewCardinalityPolicy.FIRST_ROW);
		assertThat(ConsoleJoinPolicyResolver.parse("x")).isNull();
	}

	private static ViewRelation relation(String table, int maxRows) {
		return new ViewRelation(table, table, null, 2, 1, maxRows, maxRows + 1, List.of());
	}
}
