package com.challenge.codephoenix.infrastructure.packaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.BusinessRule;

/**
 * Construye la matriz de trazabilidad regla → líneas legacy → elemento moderno
 * leyendo los comentarios de trazabilidad del código generado.
 */
@Component
public class TraceabilityMatrix {

	public record Link(String ruleId, String file, int line, String element) {
	}

	public record Row(BusinessRule rule, List<Link> links) {
	}

	private static final Pattern RULE_REF = Pattern.compile("\\bR-(\\d{2,3})\\b");
	private static final Pattern DECLARATION = Pattern
		.compile("(?:class|record|interface|enum)\\s+(\\w+)|(\\w+)\\s*\\([^)]*\\)?\\s*(?:throws [\\w.,\\s]+)?\\{?\\s*$");

	public List<Row> build(List<BusinessRule> rules, Map<String, String> files) {
		List<Link> links = new ArrayList<>();
		files.forEach((path, content) -> {
			if (!path.startsWith("src/main/java/")) {
				return;
			}
			String[] lines = content.split("\r?\n");
			for (int i = 0; i < lines.length; i++) {
				String trimmed = lines[i].strip();
				if (!(trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))) {
					continue;
				}
				Matcher m = RULE_REF.matcher(trimmed);
				while (m.find()) {
					links.add(new Link("R-" + m.group(1), path, i + 1, nextDeclaration(lines, i)));
				}
			}
		});
		return rules.stream()
			.map(rule -> new Row(rule, links.stream().filter(l -> l.ruleId().equals(rule.getId())).distinct().toList()))
			.toList();
	}

	public long tracedRules(List<Row> rows) {
		return rows.stream().filter(r -> !r.links().isEmpty()).count();
	}

	private static String nextDeclaration(String[] lines, int from) {
		for (int j = from + 1; j < Math.min(lines.length, from + 8); j++) {
			String code = lines[j].strip();
			if (code.isEmpty() || code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
					|| code.startsWith("@")) {
				continue;
			}
			Matcher m = DECLARATION.matcher(code);
			if (m.find()) {
				return m.group(1) != null ? m.group(1) : m.group(2) + "()";
			}
			return code.length() > 60 ? code.substring(0, 60) + "…" : code;
		}
		return "-";
	}

}
