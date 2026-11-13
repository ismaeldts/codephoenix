package com.challenge.codephoenix.domain;

import java.util.List;

public record CaseResult(String id, String description, String kind, String input, boolean passed,
		List<String> legacyOutput, List<String> modernOutput, List<String> differences) {
}
