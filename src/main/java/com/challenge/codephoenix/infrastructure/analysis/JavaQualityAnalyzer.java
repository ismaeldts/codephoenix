package com.challenge.codephoenix.infrastructure.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.MaskingFinding;
import com.challenge.codephoenix.domain.QualityReport;
import com.challenge.codephoenix.domain.QualityReport.Finding;

/**
 * Quality gate estático sobre el código generado: precisión numérica, complejidad
 * por método, secretos, manejo de excepciones y reglas de dependencia de la
 * arquitectura hexagonal.
 */
@Component
public class JavaQualityAnalyzer {

	public static final int MAX_METHOD_COMPLEXITY = 10;

	private static final Pattern METHOD_START = Pattern.compile(
			"^\\s*(?:@\\w+\\s+)*(?:(?:public|private|protected|static|final|synchronized|default)\\s+)*"
					+ "(?:<[^>]+>\\s+)?[\\w.<>\\[\\],? ]+\\s+(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{\\s*$");
	private static final Pattern DECISION = Pattern.compile("\\b(if|for|while|case|catch)\\b|&&|\\|\\||\\?(?![?.])");
	private static final Pattern FLOATING_MONEY = Pattern.compile("\\b(double|float|Double|Float)\\b");
	private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");
	private static final Pattern NEW_BIGDECIMAL_DOUBLE = Pattern.compile("new\\s+BigDecimal\\s*\\(\\s*\\d+\\.\\d+");
	private static final Pattern IMPORT = Pattern.compile("^import\\s+([\\w.]+)", Pattern.MULTILINE);

	private final SecretMasker secretMasker;

	public JavaQualityAnalyzer(SecretMasker secretMasker) {
		this.secretMasker = secretMasker;
	}

	public QualityReport analyze(Map<String, String> files) {
		List<Finding> findings = new ArrayList<>();
		int maxComplexity = 0;
		int linesOfCode = 0;
		int analyzed = 0;
		for (Map.Entry<String, String> entry : files.entrySet()) {
			String path = entry.getKey();
			if (!path.endsWith(".java") || path.contains("/test/")) {
				continue;
			}
			analyzed++;
			String code = entry.getValue();
			String[] lines = code.split("\r?\n");
			linesOfCode += (int) java.util.Arrays.stream(lines).filter(l -> !l.isBlank()).count();
			boolean coreLayer = path.contains("/domain/") || path.contains("/application/");

			for (MaskingFinding secret : secretMasker.mask(code).findings()) {
				findings.add(new Finding("BLOQUEANTE", "secreto-en-codigo", path, secret.line(),
						"Posible dato sensible (" + secret.type() + ") en el código generado"));
			}
			for (int i = 0; i < lines.length; i++) {
				String line = stripComment(lines[i]);
				if (coreLayer && FLOATING_MONEY.matcher(line).find()) {
					findings.add(new Finding("CRITICO", "precision-decimal", path, i + 1,
							"Tipo de coma flotante en la lógica de negocio; usar BigDecimal"));
				}
				if (NEW_BIGDECIMAL_DOUBLE.matcher(line).find()) {
					findings.add(new Finding("CRITICO", "bigdecimal-desde-double", path, i + 1,
							"new BigDecimal(double) pierde precisión; usar new BigDecimal(\"...\")"));
				}
				if (line.contains("System.exit") && coreLayer) {
					findings.add(new Finding("MAYOR", "system-exit", path, i + 1,
							"System.exit en el núcleo de negocio"));
				}
				if (line.contains("printStackTrace")) {
					findings.add(new Finding("MAYOR", "print-stack-trace", path, i + 1,
							"printStackTrace en lugar de manejo canónico de excepciones"));
				}
			}
			Matcher emptyCatch = EMPTY_CATCH.matcher(code);
			while (emptyCatch.find()) {
				findings.add(new Finding("MAYOR", "catch-vacio", path, lineOf(code, emptyCatch.start()),
						"Bloque catch vacío: la excepción se ignora"));
			}
			if (path.contains("/domain/")) {
				Matcher imports = IMPORT.matcher(code);
				while (imports.find()) {
					String imported = imports.group(1);
					if (imported.contains(".adapter") || imported.contains(".infrastructure")
							|| imported.startsWith("java.io") || imported.startsWith("java.sql")
							|| imported.startsWith("com.sun.net")) {
						findings.add(new Finding("CRITICO", "dependencia-hexagonal", path, lineOf(code, imports.start()),
								"El dominio depende de infraestructura: " + imported));
					}
				}
			}
			for (int[] method : methods(lines)) {
				int complexity = complexity(lines, method[0], method[1]);
				maxComplexity = Math.max(maxComplexity, complexity);
				if (complexity > MAX_METHOD_COMPLEXITY) {
					findings.add(new Finding("MAYOR", "complejidad-ciclomatica", path, method[0] + 1,
							"Complejidad " + complexity + " supera el umbral de " + MAX_METHOD_COMPLEXITY));
				}
				if (method[1] - method[0] > 60) {
					findings.add(new Finding("MENOR", "metodo-largo", path, method[0] + 1,
							"Método de " + (method[1] - method[0]) + " líneas"));
				}
			}
		}
		int blockers = (int) findings.stream().filter(f -> f.severity().equals("BLOQUEANTE")).count();
		int criticals = (int) findings.stream().filter(f -> f.severity().equals("CRITICO")).count();
		return new QualityReport(findings, maxComplexity, blockers, criticals, analyzed, linesOfCode);
	}

	/** Devuelve pares [inicio, fin] (índices de línea) de cada método, por conteo de llaves. */
	private static List<int[]> methods(String[] lines) {
		List<int[]> result = new ArrayList<>();
		for (int i = 0; i < lines.length; i++) {
			String line = stripComment(lines[i]);
			Matcher m = METHOD_START.matcher(line);
			if (!m.matches() || line.contains(" class ") || line.contains(" record ") || line.contains(" new ")
					|| line.strip().startsWith("if") || line.strip().startsWith("for")
					|| line.strip().startsWith("while") || line.strip().startsWith("switch")
					|| line.strip().startsWith("catch") || line.strip().startsWith("else")) {
				continue;
			}
			int depth = 0;
			for (int j = i; j < lines.length; j++) {
				String body = stripStrings(stripComment(lines[j]));
				for (char c : body.toCharArray()) {
					if (c == '{') {
						depth++;
					}
					else if (c == '}') {
						depth--;
					}
				}
				if (depth == 0) {
					result.add(new int[] { i, j });
					break;
				}
			}
		}
		return result;
	}

	private static int complexity(String[] lines, int start, int end) {
		int decisions = 0;
		for (int i = start; i <= end; i++) {
			Matcher m = DECISION.matcher(stripStrings(stripComment(lines[i])));
			while (m.find()) {
				decisions++;
			}
		}
		return 1 + decisions;
	}

	private static int lineOf(String code, int offset) {
		return (int) code.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
	}

	private static String stripComment(String line) {
		String trimmed = line.strip();
		if (trimmed.startsWith("*") || trimmed.startsWith("/*")) {
			return "";
		}
		int idx = line.indexOf("//");
		return idx >= 0 && !line.substring(0, idx).contains("\"") ? line.substring(0, idx) : line;
	}

	private static String stripStrings(String line) {
		return line.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(?:\\\\.|[^'\\\\])'", "' '");
	}

}
