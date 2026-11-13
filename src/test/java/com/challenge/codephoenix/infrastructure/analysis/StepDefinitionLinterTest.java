package com.challenge.codephoenix.infrastructure.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class StepDefinitionLinterTest {

	private static final String GLUE = "src/test/java/demo/bdd/";

	private final StepDefinitionLinter linter = new StepDefinitionLinter();

	@Test
	void removesTheSameExpressionDeclaredWithAnotherKeyword() {
		String steps = """
				public class Steps {
				    @Entonces("la retención es {string}")
				    @Y("la retención es {string}")
				    public void retencion(String v) {
				    }
				}
				""";

		StepDefinitionLinter.Result result = linter.removeDuplicateSteps(Map.of(GLUE + "Steps.java", steps), GLUE);

		String fixed = result.files().get(GLUE + "Steps.java");
		assertTrue(fixed.contains("@Entonces(\"la retención es {string}\")"));
		assertFalse(fixed.contains("@Y("));
		assertEquals(1, result.removed().size());
	}

	@Test
	void detectsDuplicatesAcrossFilesAndKeepsDistinctExpressions() {
		Map<String, String> files = Map.of(
				GLUE + "A.java", "@Dado(\"una póliza {int}\")\npublic void a(int x) {}\n@Cuando(\"se liquida\")\npublic void b() {}",
				GLUE + "B.java", "@Dado(\"una póliza {int}\")\npublic void c(int x) {}\n@Entonces(\"queda {string}\")\npublic void d(String s) {}");

		StepDefinitionLinter.Result result = linter.removeDuplicateSteps(files, GLUE);

		assertEquals(1, result.files().size());
		assertTrue(result.files().containsKey(GLUE + "B.java"));
		assertTrue(result.files().get(GLUE + "B.java").contains("@Entonces(\"queda {string}\")"));
	}

	@Test
	void leavesCleanFilesUntouched() {
		Map<String, String> files = Map.of(GLUE + "A.java", "@Dado(\"x\")\npublic void a() {}\n@Cuando(\"y\")\npublic void b() {}",
				"src/main/java/demo/Prod.java", "@Dado(\"x\")");

		assertTrue(linter.removeDuplicateSteps(files, GLUE).files().isEmpty());
	}

}
