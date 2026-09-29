/** ConsoleJoinPolicyResolver.java
 *
 * Asks on an interactive console how to handle related tables with more than one row per grain record.
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

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.filteredpush.bdq_workbench.ingest.JoinPolicyResolver;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;

/**
 * A {@link JoinPolicyResolver} that asks the user, on the console, how to handle each related
 * table with more than one row per grain record.
 *
 * <p>Used by headless runs started from an interactive terminal. For each undecided table it shows
 * the observed multiplicity and the four policies, and reads a choice ({@code EXPAND},
 * {@code FIRST_ROW}, {@code AGGREGATE}, {@code REJECT}, or just the first letter). A blank answer
 * leaves the table undecided, which stops the run with instructions; an unrecognized answer is
 * asked again. It also prints the {@code --join-policy} options that reproduce the answers, so the
 * run can be repeated non-interactively.
 */
final class ConsoleJoinPolicyResolver implements JoinPolicyResolver {

	/** Attempts allowed per table before an unrecognized answer leaves it undecided. */
	private static final int MAX_ATTEMPTS = 3;

	private final BufferedReader in;
	private final PrintWriter out;

	/**
	 * @param in source of the user's answers
	 * @param out destination for the prompts
	 */
	ConsoleJoinPolicyResolver(BufferedReader in, PrintWriter out) {
		this.in = in;
		this.out = out;
	}

	/**
	 * Returns a resolver for the JVM's console, or {@link JoinPolicyResolver#NONE} when the JVM has
	 * no interactive console (scripts, CI, redirected input), so those runs stop with instructions
	 * instead of waiting for input.
	 *
	 * @return the resolver
	 */
	static JoinPolicyResolver forSystemConsole() {
		Console console = System.console();
		if (console == null) {
			return JoinPolicyResolver.NONE;
		}
		return new ConsoleJoinPolicyResolver(new BufferedReader(console.reader()), console.writer());
	}

	/**
	 * Asks for a policy for each undecided table.
	 *
	 * @param grainTable the grain table the view is built around
	 * @param undecided the related tables needing a decision
	 * @return the answered policies keyed by table name
	 */
	@Override
	public Map<String, DatasetViewCardinalityPolicy> resolve(String grainTable, List<ViewRelation> undecided) {
		out.println();
		out.println("Each record is one " + grainTable + " row. These related tables have more than one row for some "
				+ grainTable + " records; choose how tests should handle them:");
		for (DatasetViewCardinalityPolicy policy : DatasetViewCardinalityPolicy.values()) {
			out.println("  " + policy.name() + " - " + policy.description());
		}
		Map<String, DatasetViewCardinalityPolicy> answers = new LinkedHashMap<>();
		for (ViewRelation relation : undecided) {
			DatasetViewCardinalityPolicy policy = ask(grainTable, relation);
			if (policy != null) {
				answers.put(relation.sourceTable(), policy);
			}
		}
		if (!answers.isEmpty()) {
			StringBuilder options = new StringBuilder("To repeat these choices without prompting, add:");
			answers.forEach((table, policy) -> options.append(" --join-policy ").append(table).append('=')
					.append(policy.name()));
			out.println(options);
		}
		out.flush();
		return answers;
	}

	/**
	 * Asks for one table's policy.
	 *
	 * @param grainTable the grain table
	 * @param relation the table's observed multiplicity
	 * @return the chosen policy, or {@code null} when left undecided
	 */
	private DatasetViewCardinalityPolicy ask(String grainTable, ViewRelation relation) {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			out.print(relation.sourceTable() + " (up to " + relation.maxRowsPerCoreRecord() + " rows per " + grainTable
					+ " record; " + relation.coreRecordsWithMultipleRows() + " records have more than one) "
					+ "[EXPAND/FIRST_ROW/AGGREGATE/REJECT, blank to stop]: ");
			out.flush();
			String answer = readLine();
			if (answer == null || answer.isBlank()) {
				return null;
			}
			DatasetViewCardinalityPolicy policy = parse(answer);
			if (policy != null) {
				return policy;
			}
			out.println("Unrecognized choice: " + answer.trim());
		}
		return null;
	}

	/**
	 * Parses a policy name or its first letter, case-insensitively.
	 *
	 * @param answer the user's answer
	 * @return the policy, or {@code null} when not recognized
	 */
	static DatasetViewCardinalityPolicy parse(String answer) {
		String normalized = answer.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		for (DatasetViewCardinalityPolicy policy : DatasetViewCardinalityPolicy.values()) {
			if (policy.name().equals(normalized)
					|| normalized.length() == 1 && policy.name().charAt(0) == normalized.charAt(0)) {
				return policy;
			}
		}
		return null;
	}

	/**
	 * @return the next input line, or {@code null} at end of input
	 */
	private String readLine() {
		try {
			return in.readLine();
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to read a join policy from the console", e);
		}
	}
}
