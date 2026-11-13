package com.challenge.codephoenix.domain;

import java.util.List;

public record QualityReport(List<Finding> findings, int maxMethodComplexity, int blockers, int criticals,
		int filesAnalyzed, int linesOfCode) {

	public record Finding(String severity, String rule, String file, int line, String message) {
	}

}
