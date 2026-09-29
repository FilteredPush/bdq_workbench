package org.filteredpush.bdq_workbench.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests mapping implementation-named terms onto a record's own term names.
 */
class DarwinCoreTermResolverTest {

	@Test
	void recordTermForPrefersTheRecordsOwnKey() {
		assertThat(DarwinCoreTermResolver.recordTermFor("dwc:geodeticDatum", List.of("geodeticDatum", "countryCode"),
				List.of())).isEqualTo("geodeticDatum");
		assertThat(DarwinCoreTermResolver.recordTermFor("dwc:eventDate", List.of("dwc:eventDate"), List.of()))
				.isEqualTo("dwc:eventDate");
		assertThat(DarwinCoreTermResolver.recordTermFor("http://rs.tdwg.org/dwc/terms/countryCode",
				List.of("countryCode"), List.of())).isEqualTo("countryCode");
	}

	@Test
	void recordTermForFallsBackToBoundNameThenLocalName() {
		assertThat(DarwinCoreTermResolver.recordTermFor("dwc:basisOfRecord", List.of("occurrenceID"),
				List.of("dwc:basisOfRecord"))).isEqualTo("dwc:basisOfRecord");
		assertThat(DarwinCoreTermResolver.recordTermFor("dwc:basisOfRecord", List.of("occurrenceID"), List.of()))
				.isEqualTo("basisOfRecord");
		/* A new term follows the record's own naming convention. */
		assertThat(DarwinCoreTermResolver.recordTermFor("dwc:countryCode", List.of("dwc:country"), List.of()))
				.isEqualTo("dwc:countryCode");
	}
}
