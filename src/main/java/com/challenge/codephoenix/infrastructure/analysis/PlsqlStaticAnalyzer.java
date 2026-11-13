package com.challenge.codephoenix.infrastructure.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.PlsqlModel;
import com.challenge.codephoenix.domain.PlsqlModel.Column;
import com.challenge.codephoenix.domain.PlsqlModel.NonDeterminism;
import com.challenge.codephoenix.domain.PlsqlModel.Param;
import com.challenge.codephoenix.domain.PlsqlModel.Table;
import com.challenge.codephoenix.domain.StaticAnalysis;
import com.challenge.codephoenix.domain.StaticAnalysis.CallEdge;
import com.challenge.codephoenix.domain.StaticAnalysis.DataItem;
import com.challenge.codephoenix.domain.StaticAnalysis.InfraOperation;
import com.challenge.codephoenix.domain.StaticAnalysis.Paragraph;
import com.challenge.codephoenix.domain.StaticAnalysis.RiskItem;

/**
 * Análisis estático determinista de PL/SQL: esquema de tablas (DDL), firma de
 * la rutina de entrada, unidades (procedimientos y funciones anidados), grafo
 * de llamadas, tablas leídas y modificadas, complejidad, código muerto,
 * construcciones no deterministas y mapeo de tipos Oracle a Java.
 */
@Component
public class PlsqlStaticAnalyzer {

	/** El artefacto separado en DDL de esquema y unidad de programa. */
	public record Source(String schemaDdl, String program) {
	}

	private static final Pattern PROGRAM_START = Pattern.compile(
			"CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(PROCEDURE|FUNCTION|PACKAGE)\\b");
	private static final Pattern ROUTINE_HEADER = Pattern.compile(
			"CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(PROCEDURE|FUNCTION)\\s+(?:\\w+\\.)?(\\w+)");
	private static final Pattern PACKAGE_HEADER = Pattern.compile(
			"CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?PACKAGE\\s+(?!BODY\\b)(?:\\w+\\.)?(\\w+)");
	private static final Pattern CREATE_TABLE = Pattern
		.compile("CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+(?:\\w+\\.)?(\\w+)\\s*\\(");
	private static final Pattern COLUMN = Pattern.compile(
			"^\\s*\"?(\\w+)\"?\\s+([A-Z]\\w*(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*-?\\d+)?\\s*(?:BYTE|CHAR)?\\s*\\))?)(.*)$",
			Pattern.DOTALL);
	private static final Pattern PARAM = Pattern.compile(
			"^\\s*(\\w+)\\s+(?:(IN\\s+OUT|IN|OUT)\\s+)?(?:NOCOPY\\s+)?([\\w.%$#]+)", Pattern.DOTALL);
	private static final Pattern UNIT_DECL = Pattern.compile("(?<![\\w$#])(PROCEDURE|FUNCTION)\\s+(\\w+)");
	private static final Pattern DECISION = Pattern
		.compile("(?<![\\w$#])(IF|ELSIF|WHEN|WHILE|FOR|AND|OR)(?![\\w$#])");
	private static final Pattern END_BLOCK = Pattern.compile("(?<![\\w$#])END\\s+(IF|LOOP|CASE)(?![\\w$#])");
	private static final Pattern GOTO = Pattern.compile("(?<![\\w$#])GOTO\\s+\\w+");
	private static final Pattern EXTERNAL_CALL = Pattern.compile("(?<![\\w$#.])(\\w+)\\.(\\w+)\\s*\\(");
	private static final Pattern STATEMENT_CALL = Pattern.compile("^\\s*(\\w+)\\s*(\\(|;)");
	private static final Pattern READ_TABLE = Pattern.compile(
			"(?<!(?:YEAR|MONTH|DAY|HOUR|MINUTE|SECOND|LEADING|TRAILING|BOTH)\\s)(?<![\\w$#])(?:FROM|JOIN)\\s+(?:\\w+\\.)?(\\w+)");
	private static final Pattern ROWTYPE = Pattern.compile("(\\w+)%ROWTYPE");
	private static final Pattern COLUMN_TYPE = Pattern.compile("(\\w+)\\.(\\w+)%TYPE");
	private static final Pattern WRITE_TABLE = Pattern.compile(
			"(?<![\\w$#])(?:INSERT\\s+INTO|MERGE\\s+INTO|DELETE\\s+(?:FROM\\s+)?|UPDATE)\\s+(?:\\w+\\.)?(\\w+)");
	private static final Pattern SQL_OPERATION = Pattern.compile("(?<![\\w$#])(SELECT|INSERT\\s+INTO|UPDATE|"
			+ "DELETE|MERGE\\s+INTO|COMMIT|ROLLBACK|SAVEPOINT|EXECUTE\\s+IMMEDIATE|OPEN|FETCH|CLOSE|DBMS_OUTPUT|UTL_FILE)"
			+ "(?![\\w$#])");
	private static final Pattern NON_DETERMINISTIC = Pattern.compile("(?<![\\w$#])(SYSDATE|SYSTIMESTAMP|CURRENT_DATE|"
			+ "CURRENT_TIMESTAMP|LOCALTIMESTAMP|DBMS_RANDOM|SYS_GUID|\\w+\\.NEXTVAL)(?![\\w$#])");
	private static final Pattern DECLARATION = Pattern.compile("^\\s*(\\w+)\\s+(CONSTANT\\s+)?([\\w.%$#]+"
			+ "(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*-?\\d+)?\\s*(?:BYTE|CHAR)?\\s*\\))?)\\s*(?:NOT\\s+NULL\\s*)?(?:(?::=|DEFAULT)\\s*(.+))?$",
			Pattern.DOTALL);
	private static final Set<String> NOT_DECLARATIONS = Set.of("PROCEDURE", "FUNCTION", "CURSOR", "TYPE", "PRAGMA",
			"SUBTYPE", "END", "BEGIN", "EXCEPTION", "IS", "AS");
	private static final Set<String> KEYWORDS = Set.of("BEGIN", "END", "NULL", "COMMIT", "ROLLBACK", "RAISE", "RETURN",
			"EXIT", "CONTINUE", "IF", "ELSE", "ELSIF", "LOOP", "WHILE", "FOR", "CASE", "WHEN", "THEN", "GOTO", "OPEN",
			"FETCH", "CLOSE", "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "DECLARE", "EXCEPTION", "SAVEPOINT",
			"RAISE_APPLICATION_ERROR", "EXECUTE", "PIPE", "FORALL", "WITH", "VALUES", "SET", "INTO", "FROM", "WHERE",
			"AND", "OR", "NOT", "IN", "IS", "AS", "OUT", "LOCK", "OTHERS");
	private static final Set<String> NOT_TABLES = Set.of("DUAL", "TABLE", "THE", "SELECT", "OF", "NOWAIT", "SKIP",
			"WAIT", "SET");
	private static final Pattern CURSOR = Pattern.compile("(?<![\\w$#])CURSOR\\s+(\\w+)");

	private record Unit(String name, String kind, int startLine, int endLine, int depth, int declStartLine) {
	}

	/** Copia del código con comentarios (y opcionalmente literales) en blanco, conservando posiciones. */
	private record Text(String original, String noComments, String clean, int[] lineStarts) {

		int lineOf(int offset) {
			int lo = 0;
			int hi = lineStarts.length - 1;
			while (lo < hi) {
				int mid = (lo + hi + 1) >>> 1;
				if (lineStarts[mid] <= offset) {
					lo = mid;
				}
				else {
					hi = mid - 1;
				}
			}
			return lo + 1;
		}

		String cleanLine(int line) {
			int start = lineStarts[line - 1];
			int end = line < lineStarts.length ? lineStarts[line] : clean.length();
			return clean.substring(start, end).stripTrailing();
		}

		int lines() {
			return lineStarts.length;
		}

	}

	// ------------------------------------------------------------- API

	public Source split(String code) {
		Text text = prepare(code);
		Matcher m = PROGRAM_START.matcher(text.clean());
		if (!m.find()) {
			return new Source(code, "");
		}
		return new Source(code.substring(0, m.start()), code.substring(m.start()));
	}

	public StaticAnalysis analyze(String code) {
		return analyze(code, "");
	}

	/** Analiza el artefacto; {@code extraDdl} aporta tablas que no venían en él (por ejemplo, inferidas). */
	public StaticAnalysis analyze(String code, String extraDdl) {
		Text text = prepare(code);
		List<Table> schema = new ArrayList<>(parseSchema(text.clean()));
		if (extraDdl != null && !extraDdl.isBlank()) {
			Text extra = prepare(extraDdl);
			parseSchema(extra.clean()).stream()
				.filter(t -> schema.stream().noneMatch(s -> s.name().equals(t.name())))
				.forEach(schema::add);
		}

		Matcher programStart = PROGRAM_START.matcher(text.clean());
		int programOffset = programStart.find() ? programStart.start() : 0;
		int programLine = text.lineOf(programOffset);

		String entryPoint = null;
		String kind = null;
		String returnType = null;
		List<Param> params = List.of();
		Matcher routine = ROUTINE_HEADER.matcher(text.clean());
		Matcher pkg = PACKAGE_HEADER.matcher(text.clean());
		if (routine.find(programOffset) && routine.start() == programOffset) {
			kind = routine.group(1);
			entryPoint = routine.group(2);
			String[] signature = signature(text, routine.end());
			params = parseParams(signature[0]);
			returnType = signature[1];
		}
		else if (pkg.find(programOffset)) {
			Matcher firstDecl = UNIT_DECL.matcher(text.clean());
			if (firstDecl.find(pkg.end())) {
				kind = firstDecl.group(1);
				entryPoint = pkg.group(1) + "." + firstDecl.group(2);
				String[] signature = signature(text, firstDecl.end());
				params = parseParams(signature[0]);
				returnType = signature[1];
			}
		}

		List<Unit> units = units(text, programOffset, entryPoint);
		Set<String> unitNames = new LinkedHashSet<>();
		units.forEach(u -> unitNames.add(u.name()));
		// Nombres sin prefijo de paquete: dentro del cuerpo las rutinas se llaman sin él.
		Set<String> shortNames = new LinkedHashSet<>();
		units.forEach(u -> shortNames.add(shortName(u.name())));
		Set<String> publicRoutines = publicRoutines(text, programOffset);

		List<DataItem> dataItems = new ArrayList<>();
		for (Param p : params) {
			dataItems.add(new DataItem(1, p.name(), p.type(), p.mode(), null, programLine, javaType(p.type(), schema)));
		}
		for (Unit unit : units) {
			dataItems.addAll(declarations(text, unit, units, schema));
		}
		Set<String> variables = new LinkedHashSet<>();
		dataItems.forEach(d -> variables.add(d.name()));

		List<CallEdge> edges = new ArrayList<>();
		List<InfraOperation> infra = new ArrayList<>();
		List<NonDeterminism> nonDeterministic = new ArrayList<>();
		Set<String> tablesRead = new LinkedHashSet<>();
		Set<String> tablesWritten = new LinkedHashSet<>();
		Map<String, Integer> complexity = new LinkedHashMap<>();
		Map<String, boolean[]> flags = new LinkedHashMap<>();
		int goToCount = 0;

		for (Unit unit : units) {
			int decisions = 0;
			boolean business = false;
			boolean infrastructure = false;
			String previous = "";
			for (int line = unit.startLine(); line <= unit.endLine(); line++) {
				if (ownerOf(units, line) != unit) {
					continue;
				}
				String clean = text.cleanLine(line);
				if (clean.isBlank()) {
					continue;
				}
				boolean statementStart = startsStatement(previous);
				previous = clean;
				String forDecisions = END_BLOCK.matcher(clean).replaceAll(" ");
				Matcher d = DECISION.matcher(forDecisions);
				while (d.find()) {
					decisions++;
				}
				if (clean.matches(".*(?<![\\w$#])(IF|CASE)(?![\\w$#]).*") || clean.contains(":=")) {
					business = true;
				}
				Matcher g = GOTO.matcher(clean);
				while (g.find()) {
					goToCount++;
					business = true;
				}
				Matcher sql = SQL_OPERATION.matcher(clean);
				while (sql.find()) {
					infra.add(new InfraOperation(sql.group(1).replaceAll("\\s+", " "), line, unit.name()));
					infrastructure = true;
				}
				Matcher nd = NON_DETERMINISTIC.matcher(clean);
				while (nd.find()) {
					nonDeterministic.add(new NonDeterminism(nd.group(1), line));
				}
				for (String other : unitNames) {
					if (other.equals(unit.name()) || line == startOf(units, other)) {
						continue;
					}
					String shortName = other.contains(".") ? other.substring(other.indexOf('.') + 1) : other;
					if (Pattern.compile("(?<![\\w$#.])" + shortName + "\\s*[(;]").matcher(clean).find()
							&& !clean.matches("\\s*END\\s+" + shortName + "\\s*;.*")) {
						edges.add(new CallEdge(unit.name(), other, "LLAMADA"));
					}
				}
				Matcher ext = EXTERNAL_CALL.matcher(clean);
				while (ext.find()) {
					String owner = ext.group(1);
					String target = owner + "." + ext.group(2);
					if (!isBuiltinPackage(owner) && !variables.contains(owner) && !unitNames.contains(target)
							&& !shortNames.contains(ext.group(2))) {
						edges.add(new CallEdge(unit.name(), target, "EXTERNA"));
					}
				}
				Matcher stmt = STATEMENT_CALL.matcher(clean);
				if (statementStart && stmt.find() && !clean.contains(":=") && !KEYWORDS.contains(stmt.group(1))
						&& !shortNames.contains(stmt.group(1)) && !variables.contains(stmt.group(1))
						&& !isBuiltinPackage(stmt.group(1)) && !isUnitHeader(clean) && !clean.contains("<<")) {
					edges.add(new CallEdge(unit.name(), stmt.group(1), "EXTERNA"));
				}
			}
			complexity.put(unit.name(), 1 + decisions);
			flags.put(unit.name(), new boolean[] { business, infrastructure });
		}

		collectTables(text.clean().substring(programOffset), schema, variables, tablesRead, tablesWritten);
		Set<String> known = new LinkedHashSet<>();
		schema.forEach(t -> known.add(t.name()));
		List<String> missing = new ArrayList<>();
		tablesRead.forEach(t -> {
			if (!known.contains(t)) {
				missing.add(t);
			}
		});
		tablesWritten.forEach(t -> {
			if (!known.contains(t) && !missing.contains(t)) {
				missing.add(t);
			}
		});

		List<CallEdge> distinctEdges = edges.stream().distinct().toList();
		Set<String> called = new LinkedHashSet<>();
		distinctEdges.forEach(e -> called.add(e.to()));
		List<Paragraph> paragraphs = new ArrayList<>();
		int totalComplexity = 0;
		for (Unit unit : units) {
			// Alcanzable: la rutina de entrada, cualquier rutina pública del paquete (se invoca desde fuera)
			// o una llamada desde otra unidad.
			boolean reachable = unit.name().equals(entryPoint) || publicRoutines.contains(shortName(unit.name()))
					|| called.contains(unit.name());
			int c = complexity.getOrDefault(unit.name(), 1);
			totalComplexity += c;
			boolean[] f = flags.getOrDefault(unit.name(), new boolean[2]);
			paragraphs.add(new Paragraph(unit.name(), unit.startLine(), unit.endLine(), c, f[0], f[1], reachable));
		}
		List<String> dead = paragraphs.stream().filter(p -> !p.reachable()).map(Paragraph::name).toList();

		int commentLines = 0;
		int codeLines = 0;
		for (int line = 1; line <= text.lines(); line++) {
			String clean = text.cleanLine(line);
			String original = originalLine(text, line);
			if (!clean.isBlank()) {
				codeLines++;
			}
			else if (!original.isBlank()) {
				commentLines++;
			}
		}

		List<PlsqlModel.Routine> routines = routines(text, programOffset, entryPoint, kind, params, returnType);
		List<PlsqlModel.Sequence> sequences = new ArrayList<>(parseSequences(text.clean()));
		if (extraDdl != null && !extraDdl.isBlank()) {
			parseSequences(prepare(extraDdl).clean()).stream()
				.filter(s -> sequences.stream().noneMatch(x -> x.name().equals(s.name())))
				.forEach(sequences::add);
		}
		// Datos de referencia: los INSERT que el artefacto ejecuta antes del programa (por ejemplo parámetros).
		List<PlsqlModel.SeedRow> seeds = SqlInsertParser.parseInserts(code.substring(0, programOffset), schema).rows();
		PlsqlModel model = new PlsqlModel(entryPoint, kind, returnType, params, routines, schema, sequences, seeds,
				List.copyOf(tablesRead), List.copyOf(tablesWritten), missing, nonDeterministic);
		return new StaticAnalysis(entryPoint == null ? "DESCONOCIDO" : entryPoint, text.lines(), codeLines,
				commentLines, paragraphs, dataItems, distinctEdges, dead, goToCount, infra, totalComplexity,
				riskMap(paragraphs, distinctEdges, nonDeterministic, units, tablesWritten, infra), List.of(), model);
	}

	/** Llamadas a rutinas o paquetes que no forman parte del artefacto. */
	public List<String> unresolvedTargets(StaticAnalysis analysis) {
		Set<String> known = new LinkedHashSet<>();
		analysis.paragraphs().forEach(p -> known.add(p.name()));
		return analysis.callGraph().stream().map(CallEdge::to).filter(t -> !known.contains(t)).distinct().toList();
	}

	/** Mapea un tipo Oracle al tipo Java que conserva su semántica. */
	public static String javaType(String type, List<Table> schema) {
		String t = type == null ? "" : type.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
		Matcher columnType = Pattern.compile("(\\w+)\\.(\\w+)%TYPE").matcher(t);
		if (columnType.matches()) {
			Optional<Column> column = schema.stream()
				.filter(s -> s.name().equals(columnType.group(1)))
				.findFirst()
				.flatMap(s -> s.column(columnType.group(2)));
			return column.map(c -> javaType(c.type(), schema) + " (" + t + ")").orElse("según " + t);
		}
		if (t.endsWith("%ROWTYPE")) {
			return "record de " + t.replace("%ROWTYPE", "");
		}
		Matcher number = Pattern.compile("(?:NUMBER|NUMERIC|DECIMAL)(?:\\((\\d+)(?:,(-?\\d+))?\\))?").matcher(t);
		if (number.matches()) {
			if (number.group(1) == null) {
				return "BigDecimal (escala libre, 38 dígitos)";
			}
			int precision = Integer.parseInt(number.group(1));
			int scale = number.group(2) == null ? 0 : Integer.parseInt(number.group(2));
			if (scale > 0) {
				return "BigDecimal(precision=" + precision + ", scale=" + scale + ", redondeo HALF_UP al asignar)";
			}
			return precision <= 9 ? "int" : precision <= 18 ? "long" : "BigInteger";
		}
		if (t.matches("(PLS_INTEGER|BINARY_INTEGER|SIMPLE_INTEGER|NATURAL|POSITIVE|INTEGER|INT|SMALLINT).*")) {
			return "int";
		}
		Matcher text = Pattern.compile("(?:VARCHAR2|VARCHAR|NVARCHAR2|CHAR|NCHAR)(?:\\((\\d+)(?:BYTE|CHAR)?\\))?").matcher(t);
		if (text.matches()) {
			return text.group(1) == null ? "String" : "String(" + text.group(1) + ")";
		}
		if (t.startsWith("CLOB") || t.startsWith("LONG")) {
			return "String";
		}
		if (t.startsWith("DATE") || t.startsWith("TIMESTAMP")) {
			return "LocalDateTime";
		}
		if (t.startsWith("BOOLEAN")) {
			return "boolean";
		}
		if (t.equals("EXCEPTION")) {
			return "excepción de dominio";
		}
		if (t.startsWith("BINARY_DOUBLE") || t.startsWith("FLOAT") || t.startsWith("BINARY_FLOAT")) {
			return "double (coma flotante en el origen)";
		}
		return "revisar: " + type;
	}

	// ------------------------------------------------------- utilidades

	private static Text prepare(String code) {
		char[] noComments = code.toCharArray();
		char[] clean = code.toCharArray();
		int i = 0;
		while (i < code.length()) {
			char c = code.charAt(i);
			if (c == '-' && i + 1 < code.length() && code.charAt(i + 1) == '-') {
				while (i < code.length() && code.charAt(i) != '\n') {
					noComments[i] = ' ';
					clean[i] = ' ';
					i++;
				}
			}
			else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
				while (i < code.length() && !(code.charAt(i) == '*' && i + 1 < code.length() && code.charAt(i + 1) == '/')) {
					if (code.charAt(i) != '\n') {
						noComments[i] = ' ';
						clean[i] = ' ';
					}
					i++;
				}
				if (i < code.length()) {
					noComments[i] = ' ';
					clean[i] = ' ';
					if (i + 1 < code.length()) {
						noComments[i + 1] = ' ';
						clean[i + 1] = ' ';
					}
					i += 2;
				}
			}
			else if (c == '\'') {
				i++;
				while (i < code.length()) {
					if (code.charAt(i) == '\'' && i + 1 < code.length() && code.charAt(i + 1) == '\'') {
						clean[i] = ' ';
						clean[i + 1] = ' ';
						i += 2;
						continue;
					}
					if (code.charAt(i) == '\'') {
						break;
					}
					if (code.charAt(i) != '\n') {
						clean[i] = ' ';
					}
					i++;
				}
				i++;
			}
			else {
				i++;
			}
		}
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int k = 0; k < code.length(); k++) {
			if (code.charAt(k) == '\n') {
				starts.add(k + 1);
			}
		}
		if (!code.isEmpty() && code.endsWith("\n")) {
			starts.removeLast();
		}
		int[] lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
		return new Text(code, new String(noComments), new String(clean).toUpperCase(Locale.ROOT), lineStarts);
	}

	private static String originalLine(Text text, int line) {
		int start = text.lineStarts()[line - 1];
		int end = line < text.lines() ? text.lineStarts()[line] : text.original().length();
		return text.original().substring(start, end).stripTrailing();
	}

	private static List<Table> parseSchema(String clean) {
		List<Table> tables = new ArrayList<>();
		Matcher m = CREATE_TABLE.matcher(clean);
		while (m.find()) {
			int close = matchingParen(clean, m.end() - 1);
			if (close < 0) {
				continue;
			}
			List<Column> columns = new ArrayList<>();
			for (String def : splitTopLevel(clean.substring(m.end(), close))) {
				Matcher col = COLUMN.matcher(def);
				if (!col.matches() || Set.of("CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK").contains(col.group(1))) {
					continue;
				}
				String rest = col.group(3);
				boolean nullable = !rest.contains("NOT NULL") && !rest.contains("PRIMARY KEY");
				columns.add(new Column(col.group(1), col.group(2).replaceAll("\\s+", ""), nullable));
			}
			List<String> references = new ArrayList<>();
			Matcher ref = REFERENCES.matcher(clean.substring(m.end(), close));
			while (ref.find()) {
				if (!ref.group(1).equals(m.group(1)) && !references.contains(ref.group(1))) {
					references.add(ref.group(1));
				}
			}
			tables.add(new Table(m.group(1), columns, references));
		}
		return tables;
	}

	private static final Pattern REFERENCES = Pattern.compile("(?<![\\w$#])REFERENCES\\s+(?:\\w+\\.)?(\\w+)");
	private static final Pattern CREATE_SEQUENCE = Pattern
		.compile("CREATE\\s+SEQUENCE\\s+(?:\\w+\\.)?(\\w+)([^;]*);");

	private static List<PlsqlModel.Sequence> parseSequences(String clean) {
		List<PlsqlModel.Sequence> sequences = new ArrayList<>();
		Matcher m = CREATE_SEQUENCE.matcher(clean);
		while (m.find()) {
			Matcher start = Pattern.compile("START\\s+WITH\\s+(-?\\d+)").matcher(m.group(2));
			Matcher increment = Pattern.compile("INCREMENT\\s+BY\\s+(-?\\d+)").matcher(m.group(2));
			sequences.add(new PlsqlModel.Sequence(m.group(1), start.find() ? Long.parseLong(start.group(1)) : 1,
					increment.find() ? Long.parseLong(increment.group(1)) : 1));
		}
		return sequences;
	}

	/** Rutinas invocables: la rutina independiente, o las declaradas en la especificación del paquete. */
	private static List<PlsqlModel.Routine> routines(Text text, int programOffset, String standaloneName,
			String standaloneKind, List<Param> standaloneParams, String standaloneReturn) {
		List<PlsqlModel.Routine> result = new ArrayList<>();
		String clean = text.clean();
		Matcher spec = PACKAGE_HEADER.matcher(clean);
		if (standaloneName != null && !standaloneName.contains(".")) {
			result.add(new PlsqlModel.Routine(standaloneName, standaloneKind, standaloneReturn, standaloneParams));
			return result;
		}
		if (!spec.find(programOffset)) {
			return result;
		}
		Matcher end = Pattern.compile("(?<![\\w$#])END\\s+" + spec.group(1) + "\\s*;").matcher(clean);
		int specEnd = end.find(spec.end()) ? end.start() : clean.length();
		Matcher decl = UNIT_DECL.matcher(clean);
		int from = spec.end();
		while (decl.find(from) && decl.start() < specEnd) {
			from = decl.end();
			String[] signature = signature(text, decl.end());
			result.add(new PlsqlModel.Routine(spec.group(1) + "." + decl.group(2), decl.group(1), signature[1],
					parseParams(signature[0])));
		}
		return result;
	}

	/** Devuelve {lista de parámetros, tipo de retorno} a partir del final del nombre de la rutina. */
	private static String[] signature(Text text, int afterName) {
		String clean = text.clean();
		int i = afterName;
		while (i < clean.length() && Character.isWhitespace(clean.charAt(i))) {
			i++;
		}
		String params = "";
		if (i < clean.length() && clean.charAt(i) == '(') {
			int close = matchingParen(clean, i);
			if (close > 0) {
				params = clean.substring(i + 1, close);
				i = close + 1;
			}
		}
		Matcher ret = Pattern.compile("\\G\\s*RETURN\\s+([\\w.%$#]+)").matcher(clean);
		String returnType = ret.find(i) ? ret.group(1) : null;
		return new String[] { params, returnType };
	}

	private static List<Param> parseParams(String list) {
		List<Param> params = new ArrayList<>();
		if (list.isBlank()) {
			return params;
		}
		for (String def : splitTopLevel(list)) {
			Matcher m = PARAM.matcher(def);
			if (m.find()) {
				String mode = m.group(2) == null ? "IN" : m.group(2).replaceAll("\\s+", " ");
				params.add(new Param(m.group(1), mode, m.group(3)));
			}
		}
		return params;
	}

	private static List<Unit> units(Text text, int programOffset, String entryPoint) {
		List<Unit> units = new ArrayList<>();
		String clean = text.clean();
		Matcher m = UNIT_DECL.matcher(clean);
		int searchFrom = programOffset;
		while (m.find(searchFrom)) {
			searchFrom = m.end();
			int afterSignature = m.end();
			Matcher isAs = Pattern.compile("(?<![\\w$#])(IS|AS)(?![\\w$#])|;").matcher(clean);
			if (!isAs.find(afterSignature) || isAs.group().equals(";")) {
				continue; // declaración adelantada o especificación de paquete
			}
			String name = m.group(2);
			Matcher end = Pattern.compile("(?<![\\w$#])END\\s+" + name + "\\s*;").matcher(clean);
			int endOffset = end.find(isAs.end()) ? end.end() : clean.length() - 1;
			boolean isEntry = entryPoint != null && (entryPoint.equals(name) || entryPoint.endsWith("." + name))
					&& units.stream().noneMatch(u -> u.name().equals(entryPoint));
			String unitName = isEntry ? entryPoint : name;
			int startLine = text.lineOf(m.start());
			int depth = (int) units.stream()
				.filter(u -> u.startLine() < startLine && u.endLine() >= text.lineOf(endOffset))
				.count();
			units.add(new Unit(unitName, m.group(1), startLine, text.lineOf(endOffset), depth,
					text.lineOf(isAs.start()) + 1));
		}
		return units;
	}

	/** Unidad más interna que contiene la línea. */
	private static Unit ownerOf(List<Unit> units, int line) {
		Unit owner = null;
		for (Unit u : units) {
			if (line >= u.startLine() && line <= u.endLine() && (owner == null || u.depth() > owner.depth())) {
				owner = u;
			}
		}
		return owner;
	}

	private static int startOf(List<Unit> units, String name) {
		return units.stream().filter(u -> u.name().equals(name)).mapToInt(Unit::startLine).findFirst().orElse(-1);
	}

	private static List<DataItem> declarations(Text text, Unit unit, List<Unit> units, List<Table> schema) {
		List<DataItem> items = new ArrayList<>();
		StringBuilder statement = new StringBuilder();
		int statementLine = 0;
		for (int line = unit.declStartLine(); line <= unit.endLine(); line++) {
			if (ownerOf(units, line) != unit) {
				continue;
			}
			String clean = text.cleanLine(line);
			if (clean.matches("\\s*BEGIN\\b.*")) {
				break;
			}
			if (clean.isBlank()) {
				continue;
			}
			int start = text.lineStarts()[line - 1];
			int end = line < text.lines() ? text.lineStarts()[line] : text.noComments().length();
			if (statement.isEmpty()) {
				statementLine = line;
			}
			statement.append(' ').append(text.noComments(), start, end);
			if (clean.stripTrailing().endsWith(";")) {
				String decl = statement.toString().strip();
				decl = decl.substring(0, decl.length() - 1).strip();
				statement.setLength(0);
				if (decl.matches("(?is)^(IS|AS)\\b.*")) {
					decl = decl.substring(2).strip();
				}
				Matcher m = DECLARATION.matcher(decl);
				if (m.matches() && !NOT_DECLARATIONS.contains(m.group(1).toUpperCase(Locale.ROOT))
						&& !m.group(1).equalsIgnoreCase("RETURN")) {
					String type = m.group(3).replaceAll("\\s+", "");
					items.add(new DataItem(2, m.group(1).toUpperCase(Locale.ROOT), type,
							m.group(2) != null ? "CONSTANT" : "VARIABLE", m.group(4) == null ? null : m.group(4).strip(),
							statementLine, javaType(type, schema)));
				}
			}
		}
		return items;
	}

	private static void collectTables(String program, List<Table> schema, Set<String> variables, Set<String> read,
			Set<String> written) {
		Set<String> knownTables = new LinkedHashSet<>();
		schema.forEach(t -> knownTables.add(t.name()));
		Set<String> notTables = new LinkedHashSet<>(variables);
		Matcher cursor = CURSOR.matcher(program);
		while (cursor.find()) {
			notTables.add(cursor.group(1));
		}
		variables = notTables;
		Matcher w = WRITE_TABLE.matcher(program);
		while (w.find()) {
			String name = w.group(1);
			if (!NOT_TABLES.contains(name) && !variables.contains(name) && !name.equals("SET")) {
				written.add(name);
			}
		}
		Matcher r = READ_TABLE.matcher(program);
		while (r.find()) {
			String name = r.group(1);
			if (!NOT_TABLES.contains(name) && !variables.contains(name)) {
				read.add(name);
			}
		}
		Matcher rowtype = ROWTYPE.matcher(program);
		while (rowtype.find()) {
			if (knownTables.contains(rowtype.group(1)) || !variables.contains(rowtype.group(1))) {
				read.add(rowtype.group(1));
			}
		}
		Matcher columnType = COLUMN_TYPE.matcher(program);
		while (columnType.find()) {
			if (!variables.contains(columnType.group(1))) {
				read.add(columnType.group(1));
			}
		}
	}

	private static List<RiskItem> riskMap(List<Paragraph> paragraphs, List<CallEdge> edges,
			List<NonDeterminism> nonDeterministic, List<Unit> units, Set<String> tablesWritten,
			List<InfraOperation> infra) {
		List<RiskItem> risks = new ArrayList<>();
		for (Paragraph p : paragraphs) {
			List<String> reasons = new ArrayList<>();
			if (!p.reachable()) {
				reasons.add("código muerto: no se invoca");
			}
			if (p.complexity() >= 6) {
				reasons.add("complejidad ciclomática " + p.complexity());
			}
			boolean writes = infra.stream()
				.anyMatch(o -> o.paragraph().equals(p.name()) && o.verb().matches("INSERT INTO|UPDATE|DELETE|MERGE INTO"));
			if (writes) {
				reasons.add("modifica datos (" + String.join(", ", tablesWritten) + ")");
			}
			if (nonDeterministic.stream().anyMatch(n -> n.line() >= p.startLine() && n.line() <= p.endLine())) {
				reasons.add("usa funciones no deterministas (fecha del sistema, aleatorios o secuencias)");
			}
			if (edges.stream().anyMatch(e -> e.from().equals(p.name()) && e.kind().equals("EXTERNA"))) {
				reasons.add("depende de rutinas externas");
			}
			String level = !p.reachable() ? "BAJO" : (p.complexity() >= 6 || writes) ? "ALTO"
					: reasons.isEmpty() ? "BAJO" : "MEDIO";
			risks.add(new RiskItem(p.name(), level, reasons.isEmpty() ? "lógica simple" : String.join("; ", reasons)));
		}
		return risks;
	}

	private static String shortName(String name) {
		return name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
	}

	/**
	 * Una línea empieza una instrucción si la anterior terminó una (;), abrió un bloque o es una
	 * etiqueta. Evita confundir la continuación de una expresión (por ejemplo "TO_CHAR(...));") con
	 * una llamada a una rutina.
	 */
	private static boolean startsStatement(String previousCleanLine) {
		String prev = previousCleanLine.strip();
		if (prev.isEmpty() || prev.endsWith(";") || prev.endsWith(">>")) {
			return true;
		}
		return prev.matches("(?s).*(?<![\\w$#])(BEGIN|THEN|ELSE|LOOP|IS|AS|DECLARE|EXCEPTION)$");
	}

	/** Rutinas declaradas en la especificación del paquete: son la interfaz pública. */
	private static Set<String> publicRoutines(Text text, int programOffset) {
		Set<String> names = new LinkedHashSet<>();
		String clean = text.clean();
		Matcher spec = PACKAGE_HEADER.matcher(clean);
		if (!spec.find(programOffset)) {
			return names;
		}
		Matcher end = Pattern.compile("(?<![\\w$#])END\\s+" + spec.group(1) + "\\s*;").matcher(clean);
		int specEnd = end.find(spec.end()) ? end.start() : clean.length();
		Matcher decl = UNIT_DECL.matcher(clean.substring(spec.end(), specEnd));
		while (decl.find()) {
			names.add(decl.group(2));
		}
		return names;
	}

	private static boolean isBuiltinPackage(String name) {
		return name.startsWith("DBMS_") || name.startsWith("UTL_") || name.startsWith("APEX_")
				|| Set.of("SYS", "STANDARD", "OWA_UTIL", "HTP").contains(name);
	}

	private static boolean isUnitHeader(String clean) {
		return clean.matches("\\s*(CREATE|PROCEDURE|FUNCTION|PACKAGE)\\b.*");
	}

	private static int matchingParen(String text, int open) {
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			if (text.charAt(i) == '(') {
				depth++;
			}
			else if (text.charAt(i) == ')' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	private static List<String> splitTopLevel(String text) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '(') {
				depth++;
			}
			else if (c == ')') {
				depth--;
			}
			else if (c == ',' && depth == 0) {
				parts.add(text.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(text.substring(start));
		return parts;
	}

}
