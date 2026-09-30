/** ReportDigest.java
 *
 * Condenses a run's responses into the quality-control findings the human-readable reports present.
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
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.SubjectRef;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * The quality-control view of a run, shared by the structured HTML and Markdown reports (and, for
 * its header facts, the text summary).
 *
 * <p>Rather than one section per input record listing every test, the digest condenses the run
 * into what a data manager acts on: which records meet every multi-record QA measure of the use
 * case, which tests find problems and how many (before and after amendment), which information
 * elements are empty in every record, which amendments are proposed and how widely, and a short,
 * capped list of the records needing attention. Tests are named by their human-readable labels and
 * records by values from the original data (see {@link #recordLabel(String)}).
 *
 * <p>Results are counted per record: where a test ran once per expanded related row, the derived
 * rollup stands for the record, and a record with problems notes how many of its rows had them.
 */
final class ReportDigest {

	/** Records named per finding before the rest are summarized as a count. */
	static final int MAX_EXAMPLE_RECORDS = 3;
	/** Records listed in the "records needing attention" table before the rest are counted. */
	static final int MAX_ATTENTION_RECORDS = 50;
	/** Records named as meeting every QA measure before the rest are counted. */
	static final int MAX_QUALITY_RECORDS = 25;
	/** Amendment proposals listed before the rest are counted. */
	static final int MAX_AMENDMENT_GROUPS = 25;
	/** Causes and amendment proposals listed among the high-impact action items. */
	static final int MAX_HIGH_IMPACT_ITEMS = 5;
	/** Related rows listed under a record needing attention before the rest are counted. */
	static final int MAX_ROWS_PER_RECORD = 10;
	/** Values shown for each related row needing attention. */
	static final int MAX_ROW_VALUES = 4;
	/** Test labels named per empty term before the rest are counted. */
	static final int MAX_TESTS_PER_TERM = 5;

	/** Prefix of the record IDs ingest synthesizes from row position when the data declares none. */
	private static final String SYNTHESIZED_ID_PREFIX = "row-";
	/** Terms whose value may be a record's identifier, named in its label when it has no catalog number. */
	private static final List<String> IDENTIFIER_TERMS = List.of(
			"occurrenceID", "materialEntityID", "eventID", "taxonID", "identificationID", "id");
	private static final String MULTIRECORD_SENTINEL = "MULTIRECORD";
	private static final String UNRESOLVED_SENTINEL = "*";
	private static final String EXTERNAL_PREREQUISITES = "EXTERNAL_PREREQUISITES_NOT_MET";
	private static final String INTERNAL_PREREQUISITES = "INTERNAL_PREREQUISITES_NOT_MET";
	private static final Set<String> AMENDMENT_STATUSES = Set.of("AMENDED", "FILLED_IN");

	private final ExecutionSummary summary;
	private final Map<String, CanonicalRecord> recordsById = new LinkedHashMap<>();
	private final Map<String, CanonicalRecord> relatedRowsByKey = new LinkedHashMap<>();
	private final Map<String, String> labelsByRecordId = new LinkedHashMap<>();
	private final Map<String, List<String>> actedUponTermsByTest = new LinkedHashMap<>();
	private List<Response> perRecordResponses;
	private Map<String, List<Response>> responseIndex;
	private final Map<String, Phase> latestPhaseByTest = new LinkedHashMap<>();

	/**
	 * @param summary the execution summary to condense
	 */
	private ReportDigest(ExecutionSummary summary) {
		this.summary = summary;
		summary.dataset().records().forEach(record -> recordsById.putIfAbsent(record.id(), record));
		for (RecordGraph graph : summary.dataset().recordGraphs()) {
			graph.relatedByRelation().forEach((relation, rows) -> rows.forEach(row ->
					relatedRowsByKey.putIfAbsent(relatedKey(relation, row.id()), row)));
		}
	}

	/**
	 * Builds the digest for one run.
	 *
	 * @param summary the execution summary
	 * @return the digest
	 */
	static ReportDigest from(ExecutionSummary summary) {
		return new ReportDigest(summary);
	}

	/**
	 * Names a test by its human-readable label, falling back to its identifier.
	 *
	 * @param testId the test identifier
	 * @return the label
	 */
	String testLabel(String testId) {
		String label = summary.metadata().testLabelsById().get(testId);
		return label == null || label.isBlank() ? testId : label;
	}

	/**
	 * Names a record by values a person can find in the original data.
	 *
	 * <p>An occurrence-like record with a catalog number is named by its institution code,
	 * collection code, and catalog number (or dataset and catalog number when the codes are
	 * absent); every record also gets the data file and line it was read from. A record with
	 * neither is named by its identifier and the term carrying it ({@code occurrenceID 2043…}),
	 * after its institution and collection codes when known; a record whose identifier was
	 * synthesized from its position is named by its position alone. Terms the record itself lacks are taken from the
	 * {@link org.filteredpush.bdq_workbench.model.RecordIdentification} gathered at ingest from its
	 * related rows, such as the material rows that carry a data package occurrence's catalog number.
	 *
	 * @param recordId the record identifier
	 * @return the record's label
	 */
	String recordLabel(String recordId) {
		return labelsByRecordId.computeIfAbsent(recordId, id -> labelFor(id, recordsById.get(id),
				summary.dataset().inputDescription().recordIdentification()));
	}

	/**
	 * Names the source row of an expanded related-row subject.
	 *
	 * @param subject the subject reference, possibly {@code null}
	 * @return e.g. {@code "identification.csv line 3"}, or {@code ""} for a core-record subject
	 */
	String subjectRowLabel(SubjectRef subject) {
		if (subject == null) {
			return "";
		}
		CanonicalRecord row = relatedRowsByKey.get(relatedKey(subject.relationName(), subject.rowRef()));
		if (row == null) {
			row = recordsById.get(subject.rowRef());
		}
		return row == null ? "" : row.sourceRow().label();
	}

	/**
	 * @return when the earliest response started, or {@code null} when there are none
	 */
	java.time.Instant runStartedAt() {
		return summary.responses().stream().map(Response::startedAt).filter(java.util.Objects::nonNull)
				.min(Comparator.naturalOrder()).orElse(null);
	}

	/**
	 * @return when the latest response finished, or {@code null} when there are none
	 */
	java.time.Instant runFinishedAt() {
		return summary.responses().stream().map(Response::finishedAt).filter(java.util.Objects::nonNull)
				.max(Comparator.naturalOrder()).orElse(null);
	}

	/**
	 * Describes the records meeting every QA measure in one line, for report headers.
	 *
	 * @return e.g. {@code "12 of 69 records meet all 4 multi-record QA measures (post-amendment)"}
	 */
	String qualityLine() {
		QualitySummary quality = qualitySummary();
		if (!quality.hasMeasures()) {
			return "not assessable: the use case defines no multi-record QA measures";
		}
		return quality.recordIds().size() + " of " + quality.totalRecords() + " record(s) meet all "
				+ quality.measureLabels().size() + " multi-record QA measure(s) ("
				+ (quality.phase() == Phase.POST_AMENDMENT ? "post-amendment" : "pre-amendment") + ")";
	}

	/**
	 * @return the number of per-record responses reporting external prerequisites not met
	 */
	int externalPrerequisiteCount() {
		return (int) perRecordResponses().stream().filter(ReportDigest::isExternalPrerequisites).count();
	}

	/**
	 * Tallies external-prerequisites-not-met responses by test label.
	 *
	 * @return counts keyed by test label, most first
	 */
	Map<String, Long> externalPrerequisitesByTest() {
		Map<String, Long> counts = new LinkedHashMap<>();
		perRecordResponses().stream()
				.filter(ReportDigest::isExternalPrerequisites)
				.forEach(response -> counts.merge(testLabel(response.testId()), 1L, Long::sum));
		return sortedByCount(counts);
	}

	/**
	 * Describes external prerequisites not met in one line, for report headers.
	 *
	 * @return e.g. {@code "12 result(s), in Coordinates in country (12); results may change when rerun"},
	 *     or {@code "none"}
	 */
	String externalPrerequisiteLine() {
		Map<String, Long> byTest = externalPrerequisitesByTest();
		if (byTest.isEmpty()) {
			return "none";
		}
		List<String> tests = new ArrayList<>();
		byTest.forEach((test, count) -> tests.add(test + " (" + count + ")"));
		return externalPrerequisiteCount() + " result(s), in " + String.join(", ", tests)
				+ "; an external service or resource was unavailable, so these results may change when rerun";
	}

	/**
	 * Finds the records meeting every multi-record QA measure of the use case, in the latest phase
	 * that has QA measures (post-amendment when present).
	 *
	 * @return the QA summary
	 */
	QualitySummary qualitySummary() {
		/*
		 * QA measure bindings carry the phase they were declared for, but are evaluated in every
		 * phase their target validation runs in; judge quality on the latest results available.
		 */
		Map<String, BuiltInMeasureSpec> byTarget = new LinkedHashMap<>();
		for (Phase candidate : List.of(Phase.POST_AMENDMENT, Phase.PRE_AMENDMENT)) {
			qaSpecs(candidate).forEach(spec -> byTarget.putIfAbsent(spec.targetTestId(), spec));
		}
		if (byTarget.isEmpty()) {
			return new QualitySummary(null, List.of(), List.of(), recordsById.size());
		}
		List<BuiltInMeasureSpec> qaSpecs = List.copyOf(byTarget.values());
		Phase phase = qaSpecs.stream().anyMatch(spec -> latestPhase(spec.targetTestId()) == Phase.POST_AMENDMENT)
				? Phase.POST_AMENDMENT
				: Phase.PRE_AMENDMENT;
		List<String> meeting = new ArrayList<>();
		for (String recordId : recordsById.keySet()) {
			boolean meetsAll = true;
			for (BuiltInMeasureSpec spec : qaSpecs) {
				List<Response> responses = recordLevel(spec.targetTestId(), latestPhase(spec.targetTestId()), recordId);
				if (responses.isEmpty() || !responses.stream().allMatch(spec::matchesQaCondition)) {
					meetsAll = false;
					break;
				}
			}
			if (meetsAll) {
				meeting.add(recordId);
			}
		}
		List<String> measureLabels = qaSpecs.stream().map(BuiltInMeasureSpec::targetTestLabel).distinct().toList();
		return new QualitySummary(phase, measureLabels, meeting, recordsById.size());
	}

	/**
	 * Summarizes each VALIDATION and ISSUE test's findings before and after amendment.
	 *
	 * @return one row per test, most post-amendment problems first
	 */
	List<TestFindings> testFindings() {
		Map<String, TestType> tests = new LinkedHashMap<>();
		perRecordResponses().stream()
				.filter(response -> response.testType() == TestType.VALIDATION || response.testType() == TestType.ISSUE)
				.forEach(response -> tests.putIfAbsent(response.testId(), response.testType()));
		List<TestFindings> findings = new ArrayList<>();
		tests.forEach((testId, type) -> findings.add(new TestFindings(
				testLabel(testId),
				type,
				phaseCounts(testId, type, Phase.PRE_AMENDMENT),
				phaseCounts(testId, type, Phase.POST_AMENDMENT),
				exampleProblemRecords(testId, type))));
		findings.sort(Comparator.comparingInt((TestFindings row) -> row.latest().problems()).reversed()
				.thenComparing(TestFindings::testLabel, String.CASE_INSENSITIVE_ORDER));
		return findings;
	}

	/**
	 * Finds the information elements the tests read that are empty in every input record.
	 *
	 * @return the consistently empty terms, alphabetically
	 */
	List<EmptyTerm> consistentlyEmptyTerms() {
		Map<String, Set<String>> testsByTerm = new LinkedHashMap<>();
		for (ImplementationBinding binding : summary.bindings()) {
			for (BoundMethodParameter bound : binding.parameterBindings()) {
				ParameterRole role = bound.parameter().role();
				if (role == ParameterRole.ACTED_UPON || role == ParameterRole.CONSULTED) {
					testsByTerm.computeIfAbsent(DarwinCoreTermResolver.localName(bound.parameter().source()),
							ignored -> new LinkedHashSet<>()).add(testLabel(binding.testId()));
				}
			}
		}
		List<EmptyTerm> empty = new ArrayList<>();
		testsByTerm.forEach((term, tests) -> {
			TermPresence presence = presence(term);
			if (!presence.hasValue() && !recordsById.isEmpty()) {
				empty.add(new EmptyTerm(term, presence.hasColumn(), List.copyOf(tests)));
			}
		});
		empty.sort(Comparator.comparing(EmptyTerm::term, String.CASE_INSENSITIVE_ORDER));
		return empty;
	}

	/**
	 * Groups the proposed amendments by test and change, ranked by how many records each affects.
	 *
	 * @return the amendment proposals, most records first
	 */
	List<AmendmentGroup> amendmentGroups() {
		Map<String, AmendmentGroup> groups = new LinkedHashMap<>();
		Map<String, Set<String>> recordsByGroup = new LinkedHashMap<>();
		for (Response response : perRecordResponses()) {
			if (response.phase() != Phase.AMENDMENT || response.amendments().isEmpty()
					|| !AMENDMENT_STATUSES.contains(response.responseStatus())) {
				continue;
			}
			response.amendments().forEach((term, proposed) -> {
				String original = originalValue(response, term);
				String localTerm = DarwinCoreTermResolver.localName(term);
				String key = response.testId() + "\u0000" + localTerm + "\u0000" + original + "\u0000" + proposed;
				groups.putIfAbsent(key, new AmendmentGroup(testLabel(response.testId()), localTerm,
						original, proposed == null ? "" : proposed, 0, 0, List.of()));
				recordsByGroup.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(response.recordId());
			});
		}
		List<AmendmentGroup> ranked = new ArrayList<>();
		Map<String, Integer> preProblems = problemCountsByRecord(Phase.PRE_AMENDMENT);
		Map<String, Integer> postProblems = problemCountsByRecord(Phase.POST_AMENDMENT);
		groups.forEach((key, group) -> {
			Set<String> records = recordsByGroup.get(key);
			int improved = (int) records.stream()
					.filter(recordId -> preProblems.getOrDefault(recordId, 0) > postProblems.getOrDefault(recordId, 0))
					.count();
			ranked.add(new AmendmentGroup(group.testLabel(), group.term(), group.originalValue(), group.proposedValue(),
					records.size(), improved, records.stream().limit(MAX_EXAMPLE_RECORDS).map(this::recordLabel).toList()));
		});
		ranked.sort(Comparator.comparingInt(AmendmentGroup::recordCount).reversed()
				.thenComparing(AmendmentGroup::testLabel, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(AmendmentGroup::term));
		return ranked;
	}

	/**
	 * Selects the findings most worth acting on first.
	 *
	 * @return the high-impact action items
	 */
	HighImpact highImpact() {
		int confirmed = 0;
		int potential = 0;
		int findings = 0;
		Set<String> nonCompliantRecords = new LinkedHashSet<>();
		Map<String, TestType> tests = new LinkedHashMap<>();
		perRecordResponses().forEach(response -> tests.putIfAbsent(response.testId(), response.testType()));
		Set<String> confirmedRecords = new LinkedHashSet<>();
		Set<String> potentialRecords = new LinkedHashSet<>();
		for (Map.Entry<String, TestType> test : tests.entrySet()) {
			Phase phase = latestPhase(test.getKey());
			for (String recordId : recordsById.keySet()) {
				List<Response> responses = recordLevel(test.getKey(), phase, recordId);
				if (test.getValue() == TestType.ISSUE) {
					if (responses.stream().anyMatch(response -> "IS_ISSUE".equals(response.responseResult()))) {
						confirmedRecords.add(recordId);
					} else if (responses.stream().anyMatch(response -> "POTENTIAL_ISSUE".equals(response.responseResult()))) {
						potentialRecords.add(recordId);
					}
				} else if (test.getValue() == TestType.VALIDATION
						&& responses.stream().anyMatch(response -> isProblem(TestType.VALIDATION, response))) {
					findings++;
					nonCompliantRecords.add(recordId);
				}
			}
		}
		confirmed = confirmedRecords.size();
		potential = potentialRecords.size();
		List<TestFindings> causes = testFindings().stream()
				.filter(row -> row.type() == TestType.VALIDATION && row.latest().problems() > 0)
				.limit(MAX_HIGH_IMPACT_ITEMS)
				.toList();
		List<AmendmentGroup> amendments = amendmentGroups().stream()
				.sorted(Comparator.comparingInt(AmendmentGroup::improvedRecords).reversed()
						.thenComparing(Comparator.comparingInt(AmendmentGroup::recordCount).reversed()))
				.limit(MAX_HIGH_IMPACT_ITEMS)
				.toList();
		return new HighImpact(confirmed, potential, findings, nonCompliantRecords.size(), causes, amendments,
				consistentlyEmptyTerms());
	}

	/**
	 * Counts, per record, the VALIDATION and ISSUE tests reporting a problem in one phase.
	 *
	 * @param phase the phase
	 * @return problem counts keyed by record identifier
	 */
	private Map<String, Integer> problemCountsByRecord(Phase phase) {
		Map<String, TestType> tests = new LinkedHashMap<>();
		perRecordResponses().forEach(response -> tests.putIfAbsent(response.testId(), response.testType()));
		Map<String, Integer> counts = new LinkedHashMap<>();
		tests.forEach((testId, type) -> {
			if (type != TestType.VALIDATION && type != TestType.ISSUE) {
				return;
			}
			for (String recordId : recordsById.keySet()) {
				if (recordLevel(testId, phase, recordId).stream().anyMatch(response -> isProblem(type, response))) {
					counts.merge(recordId, 1, Integer::sum);
				}
			}
		});
		return counts;
	}

	/**
	 * Lists the records with problems after amendment, or with proposed amendments.
	 *
	 * <p>A test evaluated once on the record puts its problem on the record. A test evaluated once
	 * per related row (an expanded view) puts each failing row's problem on that row, listed under
	 * the record it belongs to, and puts a failure of the record's own value (a term mapped from
	 * both the grain and the expanded table) on the record. Amendments are placed the same way.
	 *
	 * @return the records needing attention, most problems first
	 */
	List<AttentionRecord> recordsNeedingAttention() {
		Map<String, AttentionBuilder> builders = new LinkedHashMap<>();
		recordsById.keySet().forEach(recordId -> builders.put(recordId, new AttentionBuilder()));
		Map<String, TestType> tests = new LinkedHashMap<>();
		perRecordResponses().forEach(response -> tests.putIfAbsent(response.testId(), response.testType()));
		tests.forEach((testId, type) -> {
			if (type != TestType.VALIDATION && type != TestType.ISSUE) {
				return;
			}
			Phase phase = latestPhase(testId);
			builders.forEach((recordId, builder) -> addProblems(testId, type, phase, recordId, builder));
		});
		for (Response response : perRecordResponses()) {
			if (response.phase() == Phase.AMENDMENT && !response.amendments().isEmpty()
					&& AMENDMENT_STATUSES.contains(response.responseStatus())) {
				AttentionBuilder builder = builders.computeIfAbsent(response.recordId(), ignored -> new AttentionBuilder());
				CanonicalRecord row = relatedRow(response.subjectRef());
				response.amendments().forEach((term, proposed) -> {
					String change = DarwinCoreTermResolver.localName(term) + ": "
							+ displayValue(originalValue(response, term)) + " → " + displayValue(proposed);
					if (row == null) {
						builder.amendments.add(change);
					} else {
						builder.row(response.subjectRef(), row).amendments.add(change);
					}
				});
			}
		}
		List<AttentionRecord> attention = new ArrayList<>();
		builders.forEach((recordId, builder) -> {
			if (!builder.isEmpty()) {
				attention.add(builder.build(recordLabel(recordId)));
			}
		});
		attention.sort(Comparator.comparingInt(AttentionRecord::problemCount).reversed()
				.thenComparing(row -> -row.amendmentCount()));
		return attention;
	}

	/**
	 * Adds one test's problems for one record: on the record, or on each failing related row.
	 *
	 * @param testId the test
	 * @param type the test type
	 * @param phase the phase to read
	 * @param recordId the record
	 * @param builder the record's attention entry
	 */
	private void addProblems(String testId, TestType type, Phase phase, String recordId, AttentionBuilder builder) {
		List<Response> recordLevel = recordLevel(testId, phase, recordId);
		if (recordLevel.stream().noneMatch(response -> isProblem(type, response))) {
			return;
		}
		List<Response> details = responsesFor(testId, phase, recordId).stream()
				.filter(response -> !response.derived() && response.subjectRef() != null)
				.toList();
		boolean expanded = details.stream().anyMatch(response -> relatedRow(response.subjectRef()) != null);
		if (!expanded) {
			builder.problems.add(testLabel(testId));
			return;
		}
		boolean onRecord = false;
		boolean onRow = false;
		for (Response failing : details.stream().filter(response -> isProblem(type, response)).toList()) {
			CanonicalRecord row = relatedRow(failing.subjectRef());
			String problem = testLabel(testId) + ": " + failing.responseResult();
			if (row == null) {
				onRecord = true;
				builder.recordOwnValueProblems.add(problem);
			} else {
				onRow = true;
				AttentionBuilder.RowBuilder rowBuilder = builder.row(failing.subjectRef(), row);
				rowBuilder.problems.add(problem);
				rowBuilder.termsByTest.add(actedUponTerms(testId));
			}
		}
		if (!onRecord && !onRow) {
			/* The record-level result is a problem though no single evaluation is (an error or an
			 * undetermined evaluation decided the rollup): report it on the record. */
			builder.problems.add(testLabel(testId));
		}
	}

	/**
	 * Resolves an evaluation subject to the related row it was evaluated on.
	 *
	 * @param subject the subject reference, possibly {@code null}
	 * @return the related row, or {@code null} when the subject is the record itself
	 */
	private CanonicalRecord relatedRow(SubjectRef subject) {
		if (subject == null || subject.relationName() == null) {
			return null;
		}
		return relatedRowsByKey.get(relatedKey(subject.relationName(), subject.rowRef()));
	}

	/**
	 * Lists the terms a test acts upon, by local name, from its bindings.
	 *
	 * @param testId the test
	 * @return the acted-upon terms
	 */
	private List<String> actedUponTerms(String testId) {
		return actedUponTermsByTest.computeIfAbsent(testId, id -> summary.bindings().stream()
				.filter(binding -> id.equals(binding.testId()))
				.flatMap(binding -> binding.parameterBindings().stream())
				.filter(bound -> bound.parameter().role() == ParameterRole.ACTED_UPON)
				.map(bound -> DarwinCoreTermResolver.localName(bound.parameter().source()))
				.distinct()
				.toList());
	}

	/**
	 * Names a related row by its data file and line, else its relation and row reference.
	 *
	 * @param subject the row's subject reference
	 * @param row the row
	 * @return e.g. {@code "identification.csv line 918"}
	 */
	private static String rowLabel(SubjectRef subject, CanonicalRecord row) {
		return row.sourceRow().known() ? row.sourceRow().label() : subject.relationName() + " " + subject.rowRef();
	}

	/**
	 * Lists the tests that could not run at all, with the reason given.
	 *
	 * @return test label mapped to reason, in encounter order
	 */
	Map<String, String> testsUnableToRun() {
		Map<String, String> unable = new LinkedHashMap<>();
		for (Response response : summary.responses()) {
			if (UNRESOLVED_SENTINEL.equals(response.recordId())) {
				String reason = response.comment() == null || response.comment().isBlank()
						? response.message()
						: response.comment();
				unable.putIfAbsent(testLabel(response.testId()), reason == null ? "" : reason);
			}
		}
		return unable;
	}

	/**
	 * Counts one test's per-record outcomes in one phase.
	 *
	 * @param testId the test
	 * @param type the test type
	 * @param phase the phase
	 * @return the counts, or {@code null} when the test did not run in the phase
	 */
	private PhaseCounts phaseCounts(String testId, TestType type, Phase phase) {
		int records = 0;
		int problems = 0;
		int passed = 0;
		int internal = 0;
		int external = 0;
		int errors = 0;
		for (String recordId : recordsById.keySet()) {
			List<Response> responses = recordLevel(testId, phase, recordId);
			if (responses.isEmpty()) {
				continue;
			}
			records++;
			if (responses.stream().anyMatch(response -> isProblem(type, response))) {
				problems++;
			} else if (responses.stream().anyMatch(ReportDigest::isError)) {
				errors++;
			} else if (responses.stream().anyMatch(ReportDigest::isExternalPrerequisites)) {
				external++;
			} else if (responses.stream().anyMatch(response -> isPrerequisites(response, INTERNAL_PREREQUISITES))) {
				internal++;
			} else {
				passed++;
			}
		}
		if (records == 0) {
			return null;
		}
		int evaluations = 0;
		int problemEvaluations = 0;
		for (String recordId : recordsById.keySet()) {
			for (Response response : responsesFor(testId, phase, recordId)) {
				if (!response.derived()) {
					evaluations++;
					if (isProblem(type, response)) {
						problemEvaluations++;
					}
				}
			}
		}
		return new PhaseCounts(records, problems, passed, internal, external, errors, evaluations, problemEvaluations);
	}

	/**
	 * Lists the tests that ran once per expanded related row, rather than once per record.
	 *
	 * @return one entry per such test, in encounter order
	 */
	List<ExpandedTest> expandedTests() {
		Map<String, TestType> tests = new LinkedHashMap<>();
		perRecordResponses().forEach(response -> tests.putIfAbsent(response.testId(), response.testType()));
		List<ExpandedTest> expanded = new ArrayList<>();
		tests.forEach((testId, type) -> {
			Phase phase = type == TestType.AMENDMENT ? Phase.AMENDMENT : latestPhase(testId);
			Set<String> relations = new LinkedHashSet<>();
			Set<String> records = new LinkedHashSet<>();
			int evaluations = 0;
			int problems = 0;
			for (String recordId : recordsById.keySet()) {
				for (Response response : responsesFor(testId, phase, recordId)) {
					if (response.derived()) {
						continue;
					}
					evaluations++;
					records.add(recordId);
					if (response.subjectRef() != null) {
						relations.add(response.subjectRef().relationName());
					}
					if (isProblem(type, response)) {
						problems++;
					}
				}
			}
			if (evaluations > records.size()) {
				expanded.add(new ExpandedTest(testLabel(testId), type, evaluations, records.size(), problems,
						List.copyOf(relations)));
			}
		});
		return expanded;
	}

	/**
	 * Names a few records with problems for one test, in its latest phase.
	 *
	 * @param testId the test
	 * @param type the test type
	 * @return up to {@link #MAX_EXAMPLE_RECORDS} record labels
	 */
	private List<String> exampleProblemRecords(String testId, TestType type) {
		Phase phase = latestPhase(testId);
		List<String> examples = new ArrayList<>();
		for (String recordId : recordsById.keySet()) {
			if (examples.size() >= MAX_EXAMPLE_RECORDS) {
				break;
			}
			if (recordLevel(testId, phase, recordId).stream().anyMatch(response -> isProblem(type, response))) {
				examples.add(recordLabel(recordId));
			}
		}
		return examples;
	}

	/**
	 * Returns a record's record-level response(s) for one test in one phase: the derived rollup
	 * when there is one, otherwise the record's own response(s).
	 *
	 * @param testId the test
	 * @param phase the phase
	 * @param recordId the record
	 * @return the record-level responses, empty when the test did not run for the record
	 */
	private List<Response> recordLevel(String testId, Phase phase, String recordId) {
		List<Response> responses = responsesFor(testId, phase, recordId);
		List<Response> rollups = responses.stream().filter(Response::derived).toList();
		return rollups.isEmpty() ? responses : rollups;
	}

	/**
	 * @param testId the test
	 * @param phase the phase
	 * @param recordId the record
	 * @return every response for that test, phase, and record
	 */
	private List<Response> responsesFor(String testId, Phase phase, String recordId) {
		return responseIndex().getOrDefault(testId + "\u0000" + phase + "\u0000" + recordId, List.of());
	}

	/**
	 * @return responses indexed by test, phase, and record, built once
	 */
	private Map<String, List<Response>> responseIndex() {
		if (responseIndex == null) {
			responseIndex = new LinkedHashMap<>();
			for (Response response : perRecordResponses()) {
				responseIndex.computeIfAbsent(response.testId() + "\u0000" + response.phase() + "\u0000"
						+ response.recordId(), ignored -> new ArrayList<>()).add(response);
			}
		}
		return responseIndex;
	}

	/**
	 * @param testId the test
	 * @return the latest phase in which the test ran (post-amendment when it ran there)
	 */
	private Phase latestPhase(String testId) {
		return latestPhaseByTest.computeIfAbsent(testId, id -> perRecordResponses().stream()
				.anyMatch(response -> response.testId().equals(id) && response.phase() == Phase.POST_AMENDMENT)
						? Phase.POST_AMENDMENT
						: Phase.PRE_AMENDMENT);
	}

	/**
	 * @param phase the phase
	 * @return the QA measure specifications bound in that phase
	 */
	private List<BuiltInMeasureSpec> qaSpecs(Phase phase) {
		return summary.bindings().stream()
				.filter(binding -> binding.phase() == phase)
				.map(BuiltInMeasureSpec::from)
				.flatMap(java.util.Optional::stream)
				.filter(spec -> spec.kind() == BuiltInMeasureSpec.MeasureKind.QA)
				.toList();
	}

	/**
	 * Reads the value an amendment would replace, from the original input row it applies to.
	 *
	 * @param response the amendment response
	 * @param term the amended term, as the implementation named it
	 * @return the original value, or {@code ""} when there was none
	 */
	private String originalValue(Response response, String term) {
		CanonicalRecord row = null;
		SubjectRef subject = response.subjectRef();
		if (subject != null) {
			row = relatedRowsByKey.get(relatedKey(subject.relationName(), subject.rowRef()));
		}
		if (row == null) {
			row = recordsById.get(response.recordId());
		}
		if (row == null) {
			return "";
		}
		String key = DarwinCoreTermResolver.recordTermFor(term, row.terms().keySet(), List.of());
		String value = row.terms().get(key);
		return value == null ? "" : value;
	}

	/**
	 * Reports whether a term has a column in the input and a value in any record or related row.
	 *
	 * @param term the term's local name
	 * @return the term's presence
	 */
	private TermPresence presence(String term) {
		boolean column = false;
		List<CanonicalRecord> rows = new ArrayList<>(recordsById.values());
		rows.addAll(relatedRowsByKey.values());
		for (CanonicalRecord row : rows) {
			for (Map.Entry<String, String> entry : row.terms().entrySet()) {
				if (DarwinCoreTermResolver.localName(entry.getKey()).equalsIgnoreCase(term)) {
					column = true;
					if (entry.getValue() != null && !entry.getValue().isBlank()) {
						return new TermPresence(true, true);
					}
				}
			}
		}
		return new TermPresence(column, false);
	}

	/**
	 * Builds a record's label from its terms and source position.
	 *
	 * @param recordId the record identifier
	 * @param record the record, or {@code null} when unknown
	 * @param identification identifying terms gathered from related rows, for terms the record lacks
	 * @return the label
	 */
	private static String labelFor(String recordId, CanonicalRecord record,
			org.filteredpush.bdq_workbench.model.RecordIdentification identification) {
		if (record == null) {
			return recordId;
		}
		java.util.function.UnaryOperator<String> term = localName -> firstNonBlank(term(record, localName),
				identification.term(recordId, localName));
		String catalogNumber = term.apply("catalogNumber");
		String name = "";
		if (!catalogNumber.isBlank()) {
			String institution = term.apply("institutionCode");
			String collection = term.apply("collectionCode");
			if (!institution.isBlank() || !collection.isBlank()) {
				name = joinNonBlank(":", institution, collection, catalogNumber);
			} else {
				String dataset = firstNonBlank(term.apply("datasetName"), term.apply("datasetID"));
				name = dataset.isBlank() ? "catalog number " + catalogNumber : dataset + ":" + catalogNumber;
			}
		} else {
			name = identifierName(recordId, record, term.apply("institutionCode"), term.apply("collectionCode"));
		}
		String position = record.sourceRow().label();
		if (!name.isBlank()) {
			return position.isBlank() ? name : name + " (" + position + ")";
		}
		return position.isBlank() ? recordId : position;
	}

	/**
	 * Names a record that has no catalog number by the identifier it was read with, the term that
	 * carries it, and its institution and collection codes when known: e.g.
	 * {@code "example.org:Modified Example occurrenceID 20432407122"}.
	 *
	 * @param recordId the record identifier
	 * @param record the record
	 * @param institution the record's institution code, possibly {@code ""}
	 * @param collection the record's collection code, possibly {@code ""}
	 * @return the name, or {@code ""} when the identifier was synthesized from the row position
	 */
	private static String identifierName(String recordId, CanonicalRecord record, String institution,
			String collection) {
		if (recordId == null || recordId.isBlank() || recordId.startsWith(SYNTHESIZED_ID_PREFIX)) {
			return "";
		}
		String idTerm = IDENTIFIER_TERMS.stream()
				.filter(candidate -> recordId.equals(term(record, candidate)))
				.findFirst()
				.orElse("ID");
		return joinNonBlank(" ", joinNonBlank(":", institution, collection), idTerm + " " + recordId);
	}

	/**
	 * Reads a term by local name, case-insensitively.
	 *
	 * @param record the record
	 * @param localName the term's local name
	 * @return the trimmed value, or {@code ""} when absent
	 */
	private static String term(CanonicalRecord record, String localName) {
		for (Map.Entry<String, String> entry : record.terms().entrySet()) {
			if (DarwinCoreTermResolver.localName(entry.getKey()).equalsIgnoreCase(localName) && entry.getValue() != null
					&& !entry.getValue().isBlank()) {
				return entry.getValue().trim();
			}
		}
		return "";
	}

	/**
	 * @return the per-record responses (excluding multi-record and unresolved sentinels)
	 */
	private List<Response> perRecordResponses() {
		if (perRecordResponses == null) {
			perRecordResponses = summary.responses().stream()
					.filter(response -> response.recordId() != null
							&& !MULTIRECORD_SENTINEL.equals(response.recordId())
							&& !UNRESOLVED_SENTINEL.equals(response.recordId()))
					.toList();
		}
		return perRecordResponses;
	}

	/**
	 * @param type the test type
	 * @param response a response
	 * @return {@code true} when the response reports a data quality problem
	 */
	private static boolean isProblem(TestType type, Response response) {
		String result = response.responseResult();
		if (type == TestType.VALIDATION) {
			return "NOT_COMPLIANT".equals(result);
		}
		return "POTENTIAL_ISSUE".equals(result) || "IS_ISSUE".equals(result);
	}

	/**
	 * @param response a response
	 * @return {@code true} when the test could not run because an external resource was unavailable
	 */
	private static boolean isExternalPrerequisites(Response response) {
		return isPrerequisites(response, EXTERNAL_PREREQUISITES);
	}

	/**
	 * @param response a response
	 * @param value the prerequisites value to match
	 * @return {@code true} when the response's status or result is that value
	 */
	private static boolean isPrerequisites(Response response, String value) {
		return value.equals(response.responseStatus()) || value.equals(response.responseResult());
	}

	/**
	 * @param response a response
	 * @return {@code true} when execution failed
	 */
	private static boolean isError(Response response) {
		return response.status() == OutcomeStatus.ERROR || response.status() == OutcomeStatus.UNABLE_TO_RUN;
	}

	/**
	 * @param value a value
	 * @return the value, or {@code "(empty)"} when blank
	 */
	static String displayValue(String value) {
		return value == null || value.isBlank() ? "(empty)" : value;
	}

	/**
	 * Keys a related row by its relation and row reference.
	 *
	 * @param relation the relation name
	 * @param rowId the row reference
	 * @return the key
	 */
	private static String relatedKey(String relation, String rowId) {
		return relation + "\u0000" + rowId;
	}

	private static Map<String, Long> sortedByCount(Map<String, Long> counts) {
		Map<String, Long> sorted = new LinkedHashMap<>();
		counts.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
		return sorted;
	}

	private static String joinNonBlank(String separator, String... values) {
		List<String> parts = new ArrayList<>();
		for (String value : values) {
			if (!value.isBlank()) {
				parts.add(value);
			}
		}
		return String.join(separator, parts);
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (!value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	/**
	 * Records meeting every multi-record QA measure of the use case.
	 *
	 * @param phase the phase evaluated, or {@code null} when the use case has no QA measures
	 * @param measureLabels the QA measures' target test labels
	 * @param recordIds the records meeting every QA measure
	 * @param totalRecords the number of records evaluated
	 */
	record QualitySummary(Phase phase, List<String> measureLabels, List<String> recordIds, int totalRecords) {

		/**
		 * @return {@code true} when the use case has multi-record QA measures
		 */
		boolean hasMeasures() {
			return phase != null;
		}
	}

	/**
	 * One test's per-record outcome counts in one phase.
	 *
	 * @param records records the test ran for
	 * @param problems records with a problem (NOT_COMPLIANT, or a potential or actual issue)
	 * @param passed records without a problem
	 * @param internalPrerequisites records the test could not assess from the data given
	 * @param externalPrerequisites records the test could not assess for want of an external resource
	 * @param errors records whose evaluation failed
	 * @param evaluations the test's evaluations behind those records (one per expanded related row,
	 *     or one per record)
	 * @param problemEvaluations evaluations reporting a problem
	 */
	record PhaseCounts(int records, int problems, int passed, int internalPrerequisites, int externalPrerequisites,
			int errors, int evaluations, int problemEvaluations) {

		/**
		 * Creates counts for a test evaluated once per record.
		 *
		 * @param records records the test ran for
		 * @param problems records with a problem
		 * @param passed records without a problem
		 * @param internalPrerequisites records not assessable from the data given
		 * @param externalPrerequisites records not assessable for want of an external resource
		 * @param errors records whose evaluation failed
		 */
		PhaseCounts(int records, int problems, int passed, int internalPrerequisites, int externalPrerequisites,
				int errors) {
			this(records, problems, passed, internalPrerequisites, externalPrerequisites, errors, records, problems);
		}

		/**
		 * @return {@code true} when the test ran more than once for some record (per expanded row)
		 */
		boolean expanded() {
			return evaluations > records;
		}
	}

	/**
	 * One VALIDATION or ISSUE test's findings.
	 *
	 * @param testLabel the test's label
	 * @param type the test type
	 * @param pre pre-amendment counts, or {@code null} when not run
	 * @param post post-amendment counts, or {@code null} when not run
	 * @param exampleRecords a few records with problems in the latest phase
	 */
	record TestFindings(String testLabel, TestType type, PhaseCounts pre, PhaseCounts post, List<String> exampleRecords) {

		/**
		 * @return the post-amendment counts when available, else the pre-amendment counts
		 */
		PhaseCounts latest() {
			return post != null ? post : pre != null ? pre : new PhaseCounts(0, 0, 0, 0, 0, 0);
		}
	}

	/**
	 * An information element empty in every input record.
	 *
	 * @param term the term's local name
	 * @param presentInInput whether the input has a column for it (empty everywhere) or lacks it
	 * @param tests the labels of the tests reading it
	 */
	record EmptyTerm(String term, boolean presentInInput, List<String> tests) {
	}

	/**
	 * One proposed change, with how many records it applies to.
	 *
	 * @param testLabel the proposing amendment's label
	 * @param term the amended term's local name
	 * @param originalValue the value in the input, {@code ""} when empty
	 * @param proposedValue the proposed value
	 * @param recordCount the records the proposal applies to
	 * @param improvedRecords of those, the records with fewer problems after amendment than before
	 * @param exampleRecords a few of those records
	 */
	record AmendmentGroup(String testLabel, String term, String originalValue, String proposedValue, int recordCount,
			int improvedRecords, List<String> exampleRecords) {
	}

	/**
	 * The handful of findings most worth acting on first.
	 *
	 * @param confirmedIssueRecords records with an IS_ISSUE result after amendment
	 * @param potentialIssueRecords records with a POTENTIAL_ISSUE result after amendment
	 * @param nonComplianceFindings NOT_COMPLIANT record-level validation results after amendment
	 * @param recordsWithNonCompliance records with at least one of those
	 * @param topCauses the validations with the most non-compliant records, most first
	 * @param topAmendments the amendment proposals that improved the most records, most first
	 * @param emptyTerms the information elements empty in every record
	 */
	record HighImpact(int confirmedIssueRecords, int potentialIssueRecords, int nonComplianceFindings,
			int recordsWithNonCompliance, List<TestFindings> topCauses, List<AmendmentGroup> topAmendments,
			List<EmptyTerm> emptyTerms) {
	}

	/**
	 * A record needing attention.
	 *
	 * @param recordLabel the record's label
	 * @param problems the tests reporting a problem on the record itself after amendment
	 * @param amendments the changes proposed for the record itself
	 * @param rows the record's related rows with problems or proposed amendments, in file and line
	 *     order, at most {@link #MAX_ROWS_PER_RECORD}
	 * @param moreRows how many further related rows needing attention were left out
	 */
	record AttentionRecord(String recordLabel, List<String> problems, List<String> amendments,
			List<RowAttention> rows, int moreRows) {

		/**
		 * Creates an entry with no related rows.
		 *
		 * @param recordLabel the record's label
		 * @param problems the tests reporting a problem on the record
		 * @param amendments the changes proposed for the record
		 */
		AttentionRecord(String recordLabel, List<String> problems, List<String> amendments) {
			this(recordLabel, problems, amendments, List.of(), 0);
		}

		/**
		 * @return the problems on the record and on all of its related rows
		 */
		int problemCount() {
			return problems.size() + rows.stream().mapToInt(row -> row.problems().size()).sum();
		}

		/**
		 * @return the amendments proposed for the record and its related rows
		 */
		int amendmentCount() {
			return amendments.size() + rows.stream().mapToInt(row -> row.amendments().size()).sum();
		}
	}

	/**
	 * A related row of a record needing attention: one row of an expanded table, such as one
	 * identification of an occurrence.
	 *
	 * @param rowLabel the row's data file and line
	 * @param values the row's values of the terms its failing tests act upon, as {@code term: value}
	 * @param problems the tests reporting a problem on this row, with their results
	 * @param amendments the changes proposed for this row
	 */
	record RowAttention(String rowLabel, List<String> values, List<String> problems, List<String> amendments) {
	}

	/**
	 * Collects one record's attention entry while the tests are read.
	 */
	private static final class AttentionBuilder {
		private final List<String> problems = new ArrayList<>();
		private final List<String> recordOwnValueProblems = new ArrayList<>();
		private final List<String> amendments = new ArrayList<>();
		private final Map<String, RowBuilder> rows = new LinkedHashMap<>();

		/**
		 * Returns the builder of one related row, creating it on first use.
		 *
		 * @param subject the row's subject reference
		 * @param row the row
		 * @return the row's builder
		 */
		RowBuilder row(SubjectRef subject, CanonicalRecord row) {
			return rows.computeIfAbsent(relatedKey(subject.relationName(), subject.rowRef()),
					ignored -> new RowBuilder(subject, row));
		}

		/**
		 * @return {@code true} when nothing needs attention
		 */
		boolean isEmpty() {
			return problems.isEmpty() && recordOwnValueProblems.isEmpty() && amendments.isEmpty() && rows.isEmpty();
		}

		/**
		 * Builds the entry, listing the record's own-value failures of expanded tests after its other
		 * problems, and its rows in file and line order.
		 *
		 * @param recordLabel the record's label
		 * @return the entry
		 */
		AttentionRecord build(String recordLabel) {
			List<String> recordProblems = new ArrayList<>(problems);
			recordOwnValueProblems.stream().distinct()
					.forEach(problem -> recordProblems.add(problem + " (the record's own value)"));
			List<RowAttention> ordered = rows.values().stream()
					.sorted(Comparator.comparing((RowBuilder row) -> row.row.sourceRow().file())
							.thenComparingLong(row -> row.row.sourceRow().line()))
					.map(RowBuilder::build)
					.toList();
			int shown = Math.min(MAX_ROWS_PER_RECORD, ordered.size());
			return new AttentionRecord(recordLabel, List.copyOf(recordProblems), amendments.stream().distinct().toList(),
					ordered.subList(0, shown), ordered.size() - shown);
		}

		/**
		 * Collects one related row's problems, amendments, and the terms to show its values of.
		 */
		private static final class RowBuilder {
			private final SubjectRef subject;
			private final CanonicalRecord row;
			private final List<String> problems = new ArrayList<>();
			private final List<String> amendments = new ArrayList<>();
			private final List<List<String>> termsByTest = new ArrayList<>();

			/**
			 * @param subject the row's subject reference
			 * @param row the row
			 */
			RowBuilder(SubjectRef subject, CanonicalRecord row) {
				this.subject = subject;
				this.row = row;
			}

			/**
			 * Builds the row's entry, showing at most {@link #MAX_ROW_VALUES} of the values the row
			 * itself carries: the amended terms, then the failing tests' acted-upon terms taken one test at a time (each
			 * test's first term, then each test's second, ...), so every failure's input is shown
			 * before any test's secondary terms.
			 *
			 * @return the row's entry
			 */
			RowAttention build() {
				Set<String> terms = new LinkedHashSet<>();
				amendments.forEach(change -> terms.add(change.substring(0, change.indexOf(':'))));
				int longest = termsByTest.stream().mapToInt(List::size).max().orElse(0);
				for (int position = 0; position < longest; position++) {
					for (List<String> testTerms : termsByTest) {
						if (position < testTerms.size()) {
							terms.add(testTerms.get(position));
						}
					}
				}
				/* Only terms the row carries: a term overlaid from a single-valued table (an event's
				 * coordinates) belongs to the record, not to this row. */
				List<String> values = terms.stream()
						.filter(term -> row.terms().keySet().stream()
								.anyMatch(key -> DarwinCoreTermResolver.localName(key).equalsIgnoreCase(term)))
						.map(term -> term + ": " + displayValue(term(row, term)))
						.limit(MAX_ROW_VALUES)
						.toList();
				return new RowAttention(rowLabel(subject, row), values, problems.stream().distinct().toList(),
						amendments.stream().distinct().toList());
			}
		}
	}

	/**
	 * A test that ran once per expanded related row rather than once per record.
	 *
	 * @param testLabel the test's label
	 * @param type the test type
	 * @param evaluations its evaluations, in its latest phase (the amendment phase for amendments)
	 * @param records the records those evaluations belong to
	 * @param problemEvaluations evaluations reporting a problem
	 * @param relations the expanded relations its evaluations came from
	 */
	record ExpandedTest(String testLabel, TestType type, int evaluations, int records, int problemEvaluations,
			List<String> relations) {
	}

	/**
	 * Whether a term has a column in the input and any value.
	 *
	 * @param hasColumn whether any record carries the term
	 * @param hasValue whether any record has a non-blank value for it
	 */
	private record TermPresence(boolean hasColumn, boolean hasValue) {
	}
}
