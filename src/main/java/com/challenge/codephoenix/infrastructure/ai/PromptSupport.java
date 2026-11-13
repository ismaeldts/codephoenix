package com.challenge.codephoenix.infrastructure.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.challenge.codephoenix.domain.BusinessRule;
import com.challenge.codephoenix.domain.PlsqlModel;
import com.challenge.codephoenix.domain.StaticAnalysis;

final class PromptSupport {

	private static final Pattern FILE_BLOCK = Pattern.compile("<<<FILE path=([^>\\s]+)>>>\\r?\\n(.*?)\\r?\\n<<<END>>>",
			Pattern.DOTALL);

	private PromptSupport() {
	}

	/** Código con número de línea, para que el modelo pueda citar líneas exactas. */
	static String numbered(String code) {
		String[] lines = code.split("\r?\n", -1);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			sb.append(String.format("%04d| %s%n", i + 1, lines[i]));
		}
		return sb.toString();
	}

	static String analysisSummary(StaticAnalysis analysis) {
		StringBuilder sb = new StringBuilder();
		sb.append("PROGRAMA: ").append(analysis.programId()).append('\n');
		sb.append("UNIDADES (nombre | líneas | complejidad | alcanzable):\n");
		analysis.paragraphs()
			.forEach(p -> sb.append("- ")
				.append(p.name())
				.append(" | ")
				.append(p.startLine())
				.append('-')
				.append(p.endLine())
				.append(" | ")
				.append(p.complexity())
				.append(" | ")
				.append(p.reachable() ? "sí" : "NO (código muerto)")
				.append('\n'));
		sb.append("DATOS (nombre | tipo o PIC | uso o modo | valor inicial | tipo Java equivalente):\n");
		analysis.dataItems()
			.stream()
			.filter(d -> d.picture() != null)
			.forEach(d -> sb.append("- ")
				.append(d.name())
				.append(" | ")
				.append(d.picture())
				.append(" | ")
				.append(d.usage() == null ? "DISPLAY" : d.usage())
				.append(" | ")
				.append(d.value() == null ? "-" : d.value())
				.append(" | ")
				.append(d.javaType())
				.append('\n'));
		PlsqlModel plsql = analysis.plsql();
		if (plsql != null) {
			sb.append("RUTINA DE ENTRADA: ").append(plsql.kind()).append(' ').append(plsql.entryPoint()).append('(')
				.append(plsql.params().stream().map(p -> p.name() + " " + p.mode() + " " + p.type())
					.collect(Collectors.joining(", ")))
				.append(')')
				.append(plsql.isFunction() ? " RETURN " + plsql.returnType() : "")
				.append('\n');
			sb.append("TABLAS QUE LEE: ").append(String.join(", ", plsql.tablesRead()))
				.append(" · TABLAS QUE MODIFICA: ").append(String.join(", ", plsql.tablesWritten())).append('\n');
			sb.append("ESQUEMA:\n");
			plsql.schema().forEach(t -> sb.append("- ").append(t.name()).append('(')
				.append(t.columns().stream().map(c -> c.name() + " " + c.type() + (c.nullable() ? "" : " NOT NULL"))
					.collect(Collectors.joining(", ")))
				.append(")\n"));
			plsql.nonDeterministic().forEach(n -> sb.append("NO DETERMINISTA: ").append(n.construct())
				.append(" en la línea ").append(n.line()).append('\n'));
		}
		return sb.toString();
	}

	static String rulesSpec(List<BusinessRule> rules) {
		return rules.stream()
			.map(r -> """
					[%s] %s (%s) — líneas %d-%d, párrafo %s
					  Descripción: %s
					  Condición: %s
					  Acción: %s
					  Comentario del revisor: %s""".formatted(r.getId(), r.getName(), r.getCategory(),
					r.getSourceStartLine(), r.getSourceEndLine(), r.getSourceParagraph(), r.getDescription(),
					r.getCondition(), r.getAction(), r.getReviewerComment() == null ? "-" : r.getReviewerComment()))
			.collect(Collectors.joining("\n"));
	}

	static String filesAsBlocks(Map<String, String> files) {
		return files.entrySet()
			.stream()
			.map(e -> "<<<FILE path=" + e.getKey() + ">>>\n" + e.getValue() + "\n<<<END>>>")
			.collect(Collectors.joining("\n"));
	}

	static Map<String, String> parseFiles(String reply) {
		Map<String, String> files = new LinkedHashMap<>();
		Matcher m = FILE_BLOCK.matcher(reply);
		while (m.find()) {
			String path = m.group(1).replace('\\', '/');
			if (path.startsWith("/") || path.contains("..")) {
				continue;
			}
			files.put(path, m.group(2));
		}
		return files;
	}

}
