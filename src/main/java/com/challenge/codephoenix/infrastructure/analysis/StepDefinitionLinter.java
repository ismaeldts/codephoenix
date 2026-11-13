package com.challenge.codephoenix.infrastructure.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Corrige de forma determinista las definiciones de pasos de Cucumber antes de ejecutarlas.
 *
 * <p>Cucumber ignora la palabra clave (Dado, Cuando, Entonces, Y, Pero) al enlazar un paso con su
 * definición: si la misma expresión aparece dos veces —típicamente {@code @Entonces("x")} y
 * {@code @Y("x")} sobre el mismo método— lanza {@code DuplicateStepDefinitionException} y fallan
 * TODOS los escenarios. Aquí se conserva la primera anotación de cada expresión y se eliminan las
 * repetidas.
 */
@Component
public class StepDefinitionLinter {

	public record Result(Map<String, String> files, List<String> removed) {
	}

	private static final Pattern STEP_ANNOTATION = Pattern
		.compile("^\\s*@(Dado|Dada|Dados|Dadas|Cuando|Entonces|Y|E|Pero|Given|When|Then|And|But)\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)\\s*$");

	/** Devuelve solo los archivos que cambiaron y la lista de anotaciones eliminadas. */
	public Result removeDuplicateSteps(Map<String, String> files, String glueDir) {
		Map<String, String> changed = new LinkedHashMap<>();
		List<String> removed = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		files.entrySet()
			.stream()
			.filter(e -> e.getKey().startsWith(glueDir) && e.getKey().endsWith(".java"))
			.sorted(Map.Entry.comparingByKey())
			.forEach(e -> {
				String[] lines = e.getValue().split("\n", -1);
				List<String> kept = new ArrayList<>();
				boolean modified = false;
				for (int i = 0; i < lines.length; i++) {
					Matcher m = STEP_ANNOTATION.matcher(lines[i].replace("\r", ""));
					if (m.matches() && !seen.add(m.group(2))) {
						removed.add(e.getKey().substring(e.getKey().lastIndexOf('/') + 1) + ":" + (i + 1) + " @"
								+ m.group(1) + "(\"" + m.group(2) + "\")");
						modified = true;
						continue;
					}
					kept.add(lines[i]);
				}
				if (modified) {
					changed.put(e.getKey(), String.join("\n", kept));
				}
			});
		return new Result(changed, removed);
	}

}
