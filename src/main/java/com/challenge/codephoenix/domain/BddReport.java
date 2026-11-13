package com.challenge.codephoenix.domain;

import java.util.List;

/** Resultado de ejecutar los escenarios Gherkin (Cucumber) contra el código generado. */
public record BddReport(boolean executed, int scenarios, int passed, int failed, List<String> coveredRules,
		List<String> uncoveredRules, String output) {

	public boolean allPassed() {
		return executed && scenarios > 0 && failed == 0 && passed == scenarios;
	}

}
