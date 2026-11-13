package com.challenge.codephoenix.domain;

import java.util.List;

public record EquivalenceReport(int total, int passed, int failed, int attempt, List<CaseResult> cases) {

	public boolean allPassed() {
		return total > 0 && failed == 0;
	}

}
