package com.challenge.codephoenix.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.challenge.codephoenix.domain.EquivalenceReport;
import com.challenge.codephoenix.domain.TestCase;

class EquivalenceComparatorTest {

	private final List<TestCase> cases = List.of(new TestCase("TC01", "nominal", "NOMINAL", "x", List.of()));

	@Test
	void ignoresFieldPaddingButNotValues() {
		EquivalenceReport report = EquivalenceComparator.compare(cases,
				Map.of("TC01", List.of("HDR| 18.0000| 12|     9168.00")), Map.of("TC01", List.of("HDR|18.0000|12|9168.00")),
				1);

		assertTrue(report.allPassed());
	}

	@Test
	void reportsCentDifferences() {
		EquivalenceReport report = EquivalenceComparator.compare(cases, Map.of("TC01", List.of("HDR|18.0000|12|9168.00")),
				Map.of("TC01", List.of("HDR|18.0000|12|9168.01")), 1);

		assertFalse(report.allPassed());
		assertEquals(1, report.cases().getFirst().differences().size());
	}

	@Test
	void comparesTableRowsAsASet() {
		EquivalenceReport report = EquivalenceComparator.compare(cases,
				Map.of("TC01", List.of("OUT|P_ESTADO|LIQUIDADO", "ROW|T|ID=1|V=a", "ROW|T|ID=2|V=b")),
				Map.of("TC01", List.of("ROW|T|ID=2|V=b", "OUT|P_ESTADO|LIQUIDADO", "ROW|T|ID=1|V=a")), 1);

		assertTrue(report.allPassed());
	}

	@Test
	void reportsMissingLines() {
		EquivalenceReport report = EquivalenceComparator.compare(cases, Map.of("TC01", List.of("A", "B")),
				Map.of("TC01", List.of("A")), 1);

		assertEquals("número de líneas: legacy 2 vs. moderno 1", report.cases().getFirst().differences().getFirst());
	}

}
