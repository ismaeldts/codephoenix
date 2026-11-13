package com.challenge.codephoenix.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.challenge.codephoenix.domain.CaseResult;
import com.challenge.codephoenix.domain.EquivalenceReport;
import com.challenge.codephoenix.domain.TestCase;

/**
 * Compara la salida del legacy y del código moderno línea a línea y campo a
 * campo (los campos se separan por '|' y se comparan sin espacios de relleno).
 */
final class EquivalenceComparator {

	private static final int MAX_DIFFERENCES = 4;

	private EquivalenceComparator() {
	}

	static EquivalenceReport compare(List<TestCase> cases, Map<String, List<String>> legacy,
			Map<String, List<String>> modern, int attempt) {
		List<CaseResult> results = new ArrayList<>();
		for (TestCase testCase : cases) {
			List<String> expected = canonical(legacy.getOrDefault(testCase.id(), List.of()));
			List<String> actual = canonical(modern.getOrDefault(testCase.id(), List.of()));
			List<String> differences = differences(expected, actual);
			results.add(new CaseResult(testCase.id(), testCase.description(), testCase.kind(), testCase.input(),
					differences.isEmpty(), expected, actual, differences));
		}
		int passed = (int) results.stream().filter(CaseResult::passed).count();
		return new EquivalenceReport(results.size(), passed, results.size() - passed, attempt, results);
	}

	/**
	 * Las filas volcadas de una tabla (líneas ROW|...) no tienen un orden garantizado:
	 * se comparan como conjunto, ordenadas y al final de la salida.
	 */
	private static List<String> canonical(List<String> lines) {
		List<String> result = new ArrayList<>(lines.stream().filter(l -> !l.startsWith("ROW|")).toList());
		lines.stream()
			.filter(l -> l.startsWith("ROW|"))
			.map(l -> String.join("|", normalize(l)))
			.sorted()
			.forEach(result::add);
		return result;
	}

	private static List<String> differences(List<String> expected, List<String> actual) {
		List<String> diffs = new ArrayList<>();
		if (expected.size() != actual.size()) {
			diffs.add("número de líneas: legacy " + expected.size() + " vs. moderno " + actual.size());
		}
		for (int i = 0; i < Math.min(expected.size(), actual.size()) && diffs.size() < MAX_DIFFERENCES; i++) {
			if (!normalize(expected.get(i)).equals(normalize(actual.get(i)))) {
				diffs.add("línea " + (i + 1) + ": legacy «" + expected.get(i).strip() + "» vs. moderno «"
						+ actual.get(i).strip() + "»");
			}
		}
		return diffs;
	}

	private static List<String> normalize(String line) {
		return Arrays.stream(line.split("\\|", -1)).map(String::strip).toList();
	}

}
