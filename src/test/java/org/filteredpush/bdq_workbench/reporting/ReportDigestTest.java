package org.filteredpush.bdq_workbench.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.SubjectRef;
import org.junit.jupiter.api.Test;

/**
 * Tests the quality-control findings the human-readable reports are built from.
 */
class ReportDigestTest {

	private final ReportDigest digest = ReportDigest.from(ReportFixtures.qualityControlRun());

	@Test
	void recordsAreNamedByValuesFromTheOriginalData() {
		assertThat(digest.recordLabel("occ-1")).isEqualTo("MCZ:Herp:A-1 (occurrence.txt line 2)");
		assertThat(digest.recordLabel("occ-2")).isEqualTo("Survey:77 (occurrence.txt line 3)");
		assertThat(digest.recordLabel("occ-3")).isEqualTo("occurrence.txt line 4");
		assertThat(digest.recordLabel("unknown")).isEqualTo("unknown");
		assertThat(digest.subjectRowLabel(new SubjectRef("occ-1", "identification", "identification",
				"identification", "row-2"))).isEqualTo("identification.txt line 3");
	}

	@Test
	void testsAreNamedByLabel() {
		assertThat(digest.testLabel(ReportFixtures.DATUM)).isEqualTo("Geodetic datum standard");
		assertThat(digest.testLabel("urn:test:unlabelled")).isEqualTo("urn:test:unlabelled");
	}

	@Test
	void externalPrerequisitesAreCountedByTest() {
		assertThat(digest.externalPrerequisiteCount()).isEqualTo(2);
		assertThat(digest.externalPrerequisiteLine()).startsWith("2 result(s), in Country code not empty (2); ");
	}

	@Test
	void qualityForTheUseCaseRequiresEveryQaMeasure() {
		ReportDigest.QualitySummary quality = digest.qualitySummary();

		assertThat(quality.phase()).isEqualTo(Phase.POST_AMENDMENT);
		assertThat(quality.measureLabels()).containsExactly("Geodetic datum standard", "Country code not empty");
		assertThat(quality.recordIds()).containsExactly("occ-2");
		assertThat(digest.qualityLine()).isEqualTo("1 of 3 record(s) meet all 2 multi-record QA measure(s) "
				+ "(post-amendment)");
	}

	@Test
	void testFindingsCountRecordsBeforeAndAfterAmendment() {
		List<ReportDigest.TestFindings> findings = digest.testFindings();

		assertThat(findings).extracting(ReportDigest.TestFindings::testLabel, StructuredHtmlReportExporter::problemTransition)
				.containsExactly(
						tuple("Scientific name found", "1 of 3 records (1 of 4 evaluations)"),
						tuple("Country code not empty", "0 → 0 of 3"),
						tuple("Geodetic datum standard", "2 → 0 of 3"));
		assertThat(findings.get(0).exampleRecords()).containsExactly("MCZ:Herp:A-1 (occurrence.txt line 2)");
		assertThat(findings.get(1).latest().internalPrerequisites()).isEqualTo(1);
		assertThat(findings.get(1).latest().externalPrerequisites()).isEqualTo(1);
	}

	@Test
	void consistentlyEmptyTermsAreThoseWithNoValueInAnyRecord() {
		assertThat(digest.consistentlyEmptyTerms())
				.extracting(ReportDigest.EmptyTerm::term, ReportDigest.EmptyTerm::presentInInput)
				.containsExactly(tuple("locality", true), tuple("minimumDepthInMeters", false));
		assertThat(digest.consistentlyEmptyTerms().get(0).tests()).containsExactly("Locality and depth");
	}

	@Test
	void amendmentsAreGroupedByChangeWithTheOriginalValue() {
		assertThat(digest.amendmentGroups()).singleElement().satisfies(group -> {
			assertThat(group.term()).isEqualTo("geodeticDatum");
			assertThat(group.originalValue()).isEqualTo("WGS 84");
			assertThat(group.proposedValue()).isEqualTo("EPSG:4326");
			assertThat(group.recordCount()).isEqualTo(2);
			assertThat(group.testLabel()).isEqualTo("Geodetic datum standardized");
		});
	}

	@Test
	void recordsNeedingAttentionListProblemsWithRowCountsAndAmendments() {
		assertThat(digest.recordsNeedingAttention())
				.extracting(ReportDigest.AttentionRecord::recordLabel, ReportDigest.AttentionRecord::problems,
						ReportDigest.AttentionRecord::amendments)
				.containsExactly(
						tuple("MCZ:Herp:A-1 (occurrence.txt line 2)", List.of("Scientific name found: NOT_COMPLIANT in identification.txt line 3 (1 of 2 evaluations)"),
								List.of("geodeticDatum: WGS 84 → EPSG:4326")),
						tuple("occurrence.txt line 4", List.of(), List.of("geodeticDatum: WGS 84 → EPSG:4326")));
	}

	@Test
	void testsThatCouldNotRunAreListedByLabel() {
		assertThat(digest.testsUnableToRun()).containsExactly(Map.entry("Unresolved test", "No implementation discovered"));
	}
}
