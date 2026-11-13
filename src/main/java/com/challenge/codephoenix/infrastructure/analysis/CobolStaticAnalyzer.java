package com.challenge.codephoenix.infrastructure.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.StaticAnalysis;
import com.challenge.codephoenix.domain.StaticAnalysis.CallEdge;
import com.challenge.codephoenix.domain.StaticAnalysis.DataItem;
import com.challenge.codephoenix.domain.StaticAnalysis.InfraOperation;
import com.challenge.codephoenix.domain.StaticAnalysis.Paragraph;
import com.challenge.codephoenix.domain.StaticAnalysis.RiskItem;

/**
 * Análisis estático determinista de COBOL en formato fijo (columnas 8-72).
 * Produce el inventario de párrafos, el grafo de llamadas, el código muerto,
 * la complejidad ciclomática y el mapeo de tipos legacy a tipos Java.
 */
@Component
public class CobolStaticAnalyzer {

	private static final Pattern PROGRAM_ID = Pattern.compile("PROGRAM-ID\\.\\s*([A-Z0-9-]+)");
	private static final Pattern PARAGRAPH = Pattern.compile("^([A-Z0-9][A-Z0-9-]*)(\\s+SECTION)?\\.\\s*$");
	private static final Pattern DATA_ENTRY = Pattern.compile("^\\s*(\\d{2})\\s+([A-Z0-9-]+)(.*)$");
	private static final Pattern PICTURE = Pattern.compile("PIC(?:TURE)?\\s+(?:IS\\s+)?(\\S+)");
	private static final Pattern USAGE = Pattern
		.compile("\\b(COMP-3|COMP-5|COMP|BINARY|PACKED-DECIMAL|COMPUTATIONAL-3|COMPUTATIONAL)\\b");
	private static final Pattern VALUE = Pattern.compile("\\bVALUE\\s+(?:IS\\s+)?(.+)$");
	private static final Pattern PERFORM = Pattern
		.compile("\\bPERFORM\\s+([A-Z0-9][A-Z0-9-]*)(?:\\s+(?:THRU|THROUGH)\\s+([A-Z0-9][A-Z0-9-]*))?");
	private static final Pattern GO_TO = Pattern.compile("\\bGO\\s+TO\\s+([A-Z0-9][A-Z0-9-]*)");
	private static final Pattern DECISION = Pattern.compile("(?<![\\w-])(IF|WHEN|UNTIL|AND|OR)(?![\\w-])");
	private static final Pattern WHEN_OTHER = Pattern.compile("\\bWHEN\\s+OTHER\\b");
	private static final Pattern INFRA_VERB = Pattern.compile(
			"(?<![\\w-])(ACCEPT|DISPLAY|READ|WRITE|REWRITE|OPEN|CLOSE|DELETE|START|EXEC\\s+SQL|EXEC\\s+CICS|CALL|STOP\\s+RUN|GOBACK)(?![\\w-])");
	private static final Pattern BUSINESS_VERB = Pattern
		.compile("(?<![\\w-])(COMPUTE|ADD|SUBTRACT|MULTIPLY|DIVIDE|IF|EVALUATE)(?![\\w-])");
	private static final Pattern STRING_LITERAL = Pattern.compile("\"[^\"]*\"|'[^']*'");
	private static final Set<String> INLINE_PERFORM_KEYWORDS = Set.of("VARYING", "UNTIL", "WITH", "TEST", "TIMES");

	private record SourceLine(int number, String text) {
	}

	private record RawParagraph(String name, int startLine, List<SourceLine> body) {
	}

	public StaticAnalysis analyze(String code) {
		String[] raw = code.split("\r?\n", -1);
		List<SourceLine> codeLines = new ArrayList<>();
		int commentLines = 0;
		for (int i = 0; i < raw.length; i++) {
			String line = raw[i];
			if (line.length() >= 7 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
				commentLines++;
				continue;
			}
			String text = line.length() > 7 ? line.substring(7, Math.min(72, line.length())) : "";
			if (!text.isBlank()) {
				codeLines.add(new SourceLine(i + 1, text.toUpperCase(Locale.ROOT)));
			}
		}

		String programId = codeLines.stream()
			.map(l -> PROGRAM_ID.matcher(l.text()))
			.filter(Matcher::find)
			.map(m -> m.group(1))
			.findFirst()
			.orElse("DESCONOCIDO");

		int procedureIndex = indexOf(codeLines, "PROCEDURE DIVISION");
		int dataIndex = indexOf(codeLines, "DATA DIVISION");
		List<DataItem> dataItems = dataIndex < 0 ? List.of()
				: parseDataItems(codeLines.subList(dataIndex + 1, procedureIndex < 0 ? codeLines.size() : procedureIndex));
		List<RawParagraph> rawParagraphs = procedureIndex < 0 ? List.of()
				: splitParagraphs(codeLines.subList(procedureIndex + 1, codeLines.size()));

		List<String> names = rawParagraphs.stream().map(RawParagraph::name).toList();
		List<CallEdge> edges = new ArrayList<>();
		List<InfraOperation> infraOps = new ArrayList<>();
		int goToCount = 0;
		for (RawParagraph paragraph : rawParagraphs) {
			for (SourceLine line : paragraph.body()) {
				String text = stripLiterals(line.text());
				Matcher perform = PERFORM.matcher(text);
				while (perform.find()) {
					if (INLINE_PERFORM_KEYWORDS.contains(perform.group(1))) {
						continue;
					}
					String kind = perform.group(2) != null ? "PERFORM THRU " + perform.group(2) : "PERFORM";
					edges.add(new CallEdge(paragraph.name(), perform.group(1), kind));
				}
				Matcher goTo = GO_TO.matcher(text);
				while (goTo.find()) {
					edges.add(new CallEdge(paragraph.name(), goTo.group(1), "GO TO"));
					goToCount++;
				}
				Matcher infra = INFRA_VERB.matcher(text);
				while (infra.find()) {
					infraOps.add(new InfraOperation(infra.group(1).replaceAll("\\s+", " "), line.number(),
							paragraph.name()));
				}
			}
		}

		Set<String> reachable = reachableParagraphs(rawParagraphs, names, edges);
		List<Paragraph> paragraphs = new ArrayList<>();
		int totalComplexity = 0;
		for (int i = 0; i < rawParagraphs.size(); i++) {
			RawParagraph p = rawParagraphs.get(i);
			int end = i + 1 < rawParagraphs.size() ? rawParagraphs.get(i + 1).startLine() - 1
					: p.body().isEmpty() ? p.startLine() : p.body().getLast().number();
			int complexity = complexity(p.body());
			totalComplexity += complexity;
			boolean business = p.body().stream().anyMatch(l -> BUSINESS_VERB.matcher(stripLiterals(l.text())).find());
			boolean infra = p.body().stream().anyMatch(l -> INFRA_VERB.matcher(stripLiterals(l.text())).find());
			paragraphs.add(new Paragraph(p.name(), p.startLine(), end, complexity, business, infra,
					reachable.contains(p.name())));
		}
		List<String> dead = paragraphs.stream().filter(p -> !p.reachable()).map(Paragraph::name).toList();

		return new StaticAnalysis(programId, raw.length, codeLines.size(), commentLines, paragraphs, dataItems,
				edges, dead, goToCount, infraOps, totalComplexity, riskMap(paragraphs, edges, dataItems),
				inputRecord(codeLines, dataItems), null);
	}

	private static final Pattern ACCEPT_TARGET = Pattern.compile("(?<![\\w-])ACCEPT\\s+([A-Z0-9][A-Z0-9-]*)");

	/** Diseño del registro que lee el primer ACCEPT: los campos elementales del grupo, en orden. */
	private static List<StaticAnalysis.RecordField> inputRecord(List<SourceLine> lines, List<DataItem> items) {
		String target = lines.stream()
			.map(l -> ACCEPT_TARGET.matcher(stripLiterals(l.text())))
			.filter(Matcher::find)
			.map(m -> m.group(1))
			.findFirst()
			.orElse(null);
		int groupIndex = -1;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).name().equals(target)) {
				groupIndex = i;
				break;
			}
		}
		List<StaticAnalysis.RecordField> fields = new ArrayList<>();
		if (groupIndex < 0) {
			return fields;
		}
		int position = 1;
		for (int i = groupIndex + 1; i < items.size() && items.get(i).level() > items.get(groupIndex).level(); i++) {
			String picture = items.get(i).picture();
			if (picture == null) {
				continue;
			}
			String expanded = expand(picture).replace("S", "");
			int vIndex = expanded.indexOf('V');
			int decimals = vIndex < 0 ? 0 : expanded.length() - vIndex - 1;
			int length = expanded.replace("V", "").length();
			fields.add(new StaticAnalysis.RecordField(items.get(i).name(), position, length, decimals));
			position += length;
		}
		return fields;
	}

	/** Destinos de PERFORM o GO TO que no corresponden a ningún párrafo conocido. */
	public List<String> unresolvedTargets(StaticAnalysis analysis) {
		Set<String> known = new LinkedHashSet<>();
		analysis.paragraphs().forEach(p -> known.add(p.name()));
		return analysis.callGraph().stream().map(CallEdge::to).filter(t -> !known.contains(t)).distinct().toList();
	}

	private static int indexOf(List<SourceLine> lines, String marker) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).text().contains(marker)) {
				return i;
			}
		}
		return -1;
	}

	private static List<RawParagraph> splitParagraphs(List<SourceLine> procedure) {
		List<RawParagraph> result = new ArrayList<>();
		String currentName = null;
		int currentStart = 0;
		List<SourceLine> body = new ArrayList<>();
		for (SourceLine line : procedure) {
			boolean areaA = !line.text().startsWith(" ");
			Matcher m = PARAGRAPH.matcher(line.text().strip());
			if (areaA && m.matches()) {
				if (currentName != null) {
					result.add(new RawParagraph(currentName, currentStart, body));
				}
				currentName = m.group(1);
				currentStart = line.number();
				body = new ArrayList<>();
			}
			else if (currentName != null) {
				body.add(line);
			}
		}
		if (currentName != null) {
			result.add(new RawParagraph(currentName, currentStart, body));
		}
		return result;
	}

	private static List<DataItem> parseDataItems(List<SourceLine> dataLines) {
		List<DataItem> items = new ArrayList<>();
		StringBuilder statement = new StringBuilder();
		int statementLine = 0;
		for (SourceLine line : dataLines) {
			if (line.text().strip().endsWith("SECTION.")) {
				continue;
			}
			if (statement.isEmpty()) {
				statementLine = line.number();
			}
			statement.append(' ').append(line.text().strip());
			if (line.text().strip().endsWith(".")) {
				String entry = statement.toString().strip();
				entry = entry.substring(0, entry.length() - 1);
				Matcher m = DATA_ENTRY.matcher(entry);
				if (m.matches()) {
					String rest = m.group(3);
					Matcher pic = PICTURE.matcher(rest);
					Matcher usage = USAGE.matcher(rest);
					Matcher value = VALUE.matcher(rest);
					String picture = pic.find() ? pic.group(1) : null;
					String usageValue = usage.find() ? usage.group(1) : null;
					items.add(new DataItem(Integer.parseInt(m.group(1)), m.group(2), picture, usageValue,
							value.find() ? value.group(1).strip() : null, statementLine, javaType(picture)));
				}
				statement.setLength(0);
			}
		}
		return items;
	}

	/** Mapea una cláusula PIC al tipo Java que conserva la misma precisión. */
	static String javaType(String picture) {
		if (picture == null) {
			return "grupo (record)";
		}
		String expanded = expand(picture);
		if (expanded.matches(".*[ZB*,+\\-].*") || (expanded.contains(".") && !expanded.contains("V"))) {
			return "String (formato editado)";
		}
		if (expanded.contains("X") || expanded.contains("A")) {
			return "String(" + expanded.length() + ")";
		}
		int vIndex = expanded.indexOf('V');
		int integerDigits = (int) (vIndex < 0 ? expanded : expanded.substring(0, vIndex)).chars()
			.filter(c -> c == '9')
			.count();
		int scale = vIndex < 0 ? 0 : (int) expanded.substring(vIndex).chars().filter(c -> c == '9').count();
		if (scale > 0) {
			return "BigDecimal(precision=" + (integerDigits + scale) + ", scale=" + scale + ")";
		}
		if (integerDigits <= 9) {
			return "int";
		}
		return integerDigits <= 18 ? "long" : "BigInteger";
	}

	private static String expand(String picture) {
		Matcher m = Pattern.compile("(.)\\((\\d+)\\)").matcher(picture);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1).repeat(Integer.parseInt(m.group(2)))));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static int complexity(List<SourceLine> body) {
		int decisions = 0;
		for (SourceLine line : body) {
			String text = stripLiterals(line.text());
			Matcher m = DECISION.matcher(text);
			while (m.find()) {
				decisions++;
			}
			Matcher other = WHEN_OTHER.matcher(text);
			while (other.find()) {
				decisions--;
			}
		}
		return 1 + decisions;
	}

	private static Set<String> reachableParagraphs(List<RawParagraph> paragraphs, List<String> names,
			List<CallEdge> edges) {
		Set<String> reachable = new LinkedHashSet<>();
		if (paragraphs.isEmpty()) {
			return reachable;
		}
		Map<String, List<CallEdge>> outgoing = new LinkedHashMap<>();
		edges.forEach(e -> outgoing.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e));
		Deque<String> pending = new ArrayDeque<>();
		pending.add(names.getFirst());
		while (!pending.isEmpty()) {
			String current = pending.poll();
			if (!reachable.add(current)) {
				continue;
			}
			for (CallEdge edge : outgoing.getOrDefault(current, List.of())) {
				pending.add(edge.to());
				if (edge.kind().startsWith("PERFORM THRU ")) {
					String last = edge.kind().substring("PERFORM THRU ".length());
					int from = names.indexOf(edge.to());
					int to = names.indexOf(last);
					for (int i = from; from >= 0 && i <= to; i++) {
						pending.add(names.get(i));
					}
				}
			}
		}
		return reachable;
	}

	private static List<RiskItem> riskMap(List<Paragraph> paragraphs, List<CallEdge> edges,
			List<DataItem> dataItems) {
		boolean packedDecimals = dataItems.stream().anyMatch(d -> d.usage() != null && d.usage().contains("3"));
		List<RiskItem> risks = new ArrayList<>();
		for (Paragraph p : paragraphs) {
			boolean hasGoTo = edges.stream().anyMatch(e -> e.from().equals(p.name()) && e.kind().equals("GO TO"));
			List<String> reasons = new ArrayList<>();
			if (!p.reachable()) {
				reasons.add("código muerto: no se invoca");
			}
			if (p.complexity() >= 6) {
				reasons.add("complejidad ciclomática " + p.complexity());
			}
			if (hasGoTo) {
				reasons.add("flujo con GO TO");
			}
			if (p.business() && packedDecimals) {
				reasons.add("aritmética con decimales empaquetados (COMP-3)");
			}
			String level = !p.reachable() ? "BAJO"
					: (p.complexity() >= 6 || hasGoTo) ? "ALTO" : reasons.isEmpty() ? "BAJO" : "MEDIO";
			risks.add(new RiskItem(p.name(), level, reasons.isEmpty() ? "lógica simple" : String.join("; ", reasons)));
		}
		return risks;
	}

	private static String stripLiterals(String text) {
		return STRING_LITERAL.matcher(text).replaceAll("\"\"");
	}

}
