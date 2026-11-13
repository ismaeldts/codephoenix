package com.challenge.codephoenix.infrastructure.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.challenge.codephoenix.domain.PlsqlModel;
import com.challenge.codephoenix.domain.PlsqlModel.SeedRow;
import com.challenge.codephoenix.domain.PlsqlModel.Table;

/**
 * Lee (sin ejecutarlas) sentencias SQL simples de un script: {@code INSERT INTO ... VALUES (...)} y
 * llamadas a rutinas en bloques anónimos. Convierte los literales al formato de los casos de
 * caracterización: números con punto, fechas {@code yyyy-MM-dd[ HH:mm:ss]}, nulo {@code \N}.
 */
public final class SqlInsertParser {

	public static final String NULL = "\\N";

	public record Result(List<SeedRow> rows, List<String> skipped) {
	}

	/** Llamada a una rutina encontrada en un script: nombre y argumentos por nombre (o posición "#n"). */
	public record Call(String routine, Map<String, String> arguments) {
	}

	private static final Pattern INSERT = Pattern.compile(
			"(?is)INSERT\\s+INTO\\s+(?:\\w+\\.)?(\\w+)\\s*(\\([^()]*\\))?\\s*VALUES\\s*\\(");
	private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
	private static final Pattern DATE_LITERAL = Pattern.compile("(?is)(DATE|TIMESTAMP)\\s*'([^']*)'");
	private static final Pattern TO_DATE = Pattern.compile("(?is)TO_(?:DATE|TIMESTAMP)\\s*\\(\\s*'([^']*)'\\s*(?:,\\s*'([^']*)'\\s*)?\\)");
	private static final Pattern CALL = Pattern.compile("(?is)(?<![\\w$#.])((?:\\w+\\.)?\\w+)\\s*\\((.*?)\\)\\s*;");

	private SqlInsertParser() {
	}

	/** Filas de los INSERT del script; las columnas posicionales se resuelven con el esquema. */
	public static Result parseInserts(String sql, List<Table> schema) {
		String text = stripComments(sql);
		List<SeedRow> rows = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		Matcher m = INSERT.matcher(text);
		int from = 0;
		while (m.find(from)) {
			int open = m.end() - 1;
			int close = matchingParen(text, open);
			if (close < 0) {
				skipped.add("INSERT sin cerrar en " + m.group(1));
				break;
			}
			from = close + 1;
			String tableName = m.group(1).toUpperCase(Locale.ROOT);
			Optional<Table> table = schema.stream().filter(t -> t.name().equals(tableName)).findFirst();
			if (table.isEmpty()) {
				skipped.add("Tabla desconocida: " + tableName);
				continue;
			}
			List<String> columns = m.group(2) == null
					? table.get().columns().stream().map(PlsqlModel.Column::name).toList()
					: splitTopLevel(m.group(2).substring(1, m.group(2).length() - 1)).stream()
						.map(c -> c.strip().replace("\"", "").toUpperCase(Locale.ROOT))
						.toList();
			List<String> values = splitTopLevel(text.substring(open + 1, close));
			if (values.size() != columns.size()) {
				skipped.add(tableName + ": " + values.size() + " valores para " + columns.size() + " columnas");
				continue;
			}
			Map<String, String> row = new LinkedHashMap<>();
			String problem = null;
			for (int i = 0; i < columns.size(); i++) {
				Optional<String> value = literal(values.get(i));
				if (value.isEmpty()) {
					problem = tableName + "." + columns.get(i) + ": valor no literal «" + values.get(i).strip() + "»";
					break;
				}
				row.put(columns.get(i), value.get());
			}
			if (problem != null) {
				skipped.add(problem);
				continue;
			}
			rows.add(new SeedRow(tableName, row));
		}
		return new Result(rows, skipped);
	}

	/** Llamadas a rutinas dentro de bloques BEGIN ... END de un script de ejecución. */
	public static List<Call> parseCalls(String sql) {
		String text = stripComments(sql);
		List<Call> calls = new ArrayList<>();
		Matcher block = Pattern.compile("(?is)\\bBEGIN\\b(.*?)\\bEND\\s*;").matcher(text);
		while (block.find()) {
			Matcher call = CALL.matcher(block.group(1));
			while (call.find()) {
				String name = call.group(1).toUpperCase(Locale.ROOT);
				if (name.startsWith("DBMS_") || name.equals("RAISE_APPLICATION_ERROR")) {
					continue;
				}
				Map<String, String> args = new LinkedHashMap<>();
				List<String> parts = call.group(2).isBlank() ? List.of() : splitTopLevel(call.group(2));
				for (int i = 0; i < parts.size(); i++) {
					String part = parts.get(i);
					int arrow = part.indexOf("=>");
					String key = arrow > 0 ? part.substring(0, arrow).strip().toUpperCase(Locale.ROOT) : "#" + (i + 1);
					String raw = arrow > 0 ? part.substring(arrow + 2) : part;
					args.put(key, literal(raw).orElse(raw.strip()));
				}
				calls.add(new Call(name, args));
			}
		}
		return calls;
	}

	/** Convierte un literal SQL al formato de caso; vacío si no es un literal (SYSDATE, USER, expresiones). */
	public static Optional<String> literal(String raw) {
		String v = raw.strip();
		if (v.equalsIgnoreCase("NULL")) {
			return Optional.of(NULL);
		}
		if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
			return Optional.of(v.substring(1, v.length() - 1).replace("''", "'"));
		}
		if (NUMBER.matcher(v).matches()) {
			return Optional.of(v.startsWith("+") ? v.substring(1) : v);
		}
		Matcher date = DATE_LITERAL.matcher(v);
		if (date.matches()) {
			return Optional.of(date.group(2).strip());
		}
		Matcher toDate = TO_DATE.matcher(v);
		if (toDate.matches()) {
			return convertDate(toDate.group(1), toDate.group(2));
		}
		return Optional.empty();
	}

	private static Optional<String> convertDate(String value, String format) {
		String fmt = format == null ? "YYYY-MM-DD" : format.toUpperCase(Locale.ROOT);
		Matcher m;
		switch (fmt) {
			case "YYYY-MM-DD", "YYYY-MM-DD HH24:MI:SS" -> {
				return Optional.of(value.strip());
			}
			case "DD/MM/YYYY" -> {
				m = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})").matcher(value.strip());
				return m.matches() ? Optional.of(m.group(3) + "-" + m.group(2) + "-" + m.group(1)) : Optional.empty();
			}
			case "YYYYMMDD" -> {
				m = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})").matcher(value.strip());
				return m.matches() ? Optional.of(m.group(1) + "-" + m.group(2) + "-" + m.group(3)) : Optional.empty();
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	/** Quita comentarios -- y /* *\/ respetando los literales de texto. */
	public static String stripComments(String sql) {
		StringBuilder out = new StringBuilder(sql.length());
		int i = 0;
		while (i < sql.length()) {
			char c = sql.charAt(i);
			if (c == '\'') {
				int end = i + 1;
				while (end < sql.length()) {
					if (sql.charAt(end) == '\'' && end + 1 < sql.length() && sql.charAt(end + 1) == '\'') {
						end += 2;
						continue;
					}
					if (sql.charAt(end) == '\'') {
						break;
					}
					end++;
				}
				out.append(sql, i, Math.min(sql.length(), end + 1));
				i = end + 1;
			}
			else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
				while (i < sql.length() && sql.charAt(i) != '\n') {
					i++;
				}
			}
			else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
				int end = sql.indexOf("*/", i + 2);
				i = end < 0 ? sql.length() : end + 2;
				out.append(' ');
			}
			else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	private static int matchingParen(String text, int open) {
		int depth = 0;
		boolean inString = false;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\'') {
				inString = !inString;
			}
			else if (!inString && c == '(') {
				depth++;
			}
			else if (!inString && c == ')' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	private static List<String> splitTopLevel(String text) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		boolean inString = false;
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\'') {
				inString = !inString;
			}
			else if (!inString && c == '(') {
				depth++;
			}
			else if (!inString && c == ')') {
				depth--;
			}
			else if (!inString && c == ',' && depth == 0) {
				parts.add(text.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(text.substring(start));
		return parts;
	}

}
