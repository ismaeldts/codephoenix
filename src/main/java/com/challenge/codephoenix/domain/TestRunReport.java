package com.challenge.codephoenix.domain;

public record TestRunReport(boolean compiled, int testsFound, int succeeded, int failed, double lineCoverage,
		double branchCoverage, String output) {
}
