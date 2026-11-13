package com.challenge.codephoenix.infrastructure.sandbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.challenge.codephoenix.domain.PlsqlModel;
import com.challenge.codephoenix.domain.PlsqlModel.Column;
import com.challenge.codephoenix.domain.PlsqlModel.Param;
import com.challenge.codephoenix.domain.PlsqlModel.Routine;
import com.challenge.codephoenix.domain.PlsqlModel.Table;
import com.challenge.codephoenix.domain.PlsqlModel.TypeFamily;

/**
 * Genera los scripts SQL*Plus que caracterizan un programa PL/SQL en Oracle.
 *
 * <p>Formato de un caso (también lo lee el adaptador CLI del código moderno):
 * <pre>
 * CALL|RUTINA                         (opcional; por defecto, la primera rutina pública)
 * PARAM|NOMBRE_PARAMETRO|valor
 * ROW|TABLA|COLUMNA=valor|COLUMNA=valor
 * </pre>
 * Formato de la salida: lo que la rutina imprima con DBMS_OUTPUT, luego {@code RET|valor},
 * {@code OUT|PARAMETRO|valor} o {@code ERR|sqlcode|mensaje}, y por último {@code ROW|TABLA|COL=valor|...}
 * con el contenido final de cada tabla modificada.
 *
 * <p>Para que la ejecución sea reproducible: las secuencias se reinician en cada caso, el usuario de
 * base de datos se escribe como {@value #DB_USER} y la fecha-hora del sistema como {@value #SYSTEM_TIME}
 * (o {@value #SYSTEM_DAY} para la fecha de hoy sin hora). Números sin ceros sobrantes, fechas
 * {@code yyyy-MM-dd HH:mm:ss}, nulo {@code \N}.
 */
public final class PlsqlHarness {

	public static final String NULL = "\\N";
	public static final String DB_USER = "USUARIO_BD";
	public static final String SYSTEM_TIME = "FECHA_SISTEMA";
	public static final String SYSTEM_DAY = "FECHA_SISTEMA_DIA";

	/** Un caso de caracterización ya interpretado; {@code routine} es null si se usa la rutina por defecto. */
	public record ParsedCase(String routine, Map<String, String> params, List<Row> rows) {
	}

	public record Row(String table, Map<String, String> values) {
	}

	private PlsqlHarness() {
	}

	public static ParsedCase parse(String input) {
		String routine = null;
		Map<String, String> params = new LinkedHashMap<>();
		List<Row> rows = new ArrayList<>();
		for (String raw : input.split("\r?\n")) {
			String line = raw.strip();
			if (line.isEmpty()) {
				continue;
			}
			String[] parts = line.split("\\|", -1);
			switch (parts[0]) {
				case "CALL" -> {
					if (parts.length != 2 || parts[1].isBlank()) {
						throw new IllegalArgumentException("Línea CALL mal formada: " + line);
					}
					routine = parts[1].strip().toUpperCase(Locale.ROOT);
				}
				case "PARAM" -> {
					if (parts.length != 3) {
						throw new IllegalArgumentException("Línea PARAM mal formada: " + line);
					}
					params.put(parts[1].toUpperCase(Locale.ROOT), parts[2]);
				}
				case "ROW" -> {
					if (parts.length < 3) {
						throw new IllegalArgumentException("Línea ROW mal formada: " + line);
					}
					Map<String, String> values = new LinkedHashMap<>();
					for (int i = 2; i < parts.length; i++) {
						int eq = parts[i].indexOf('=');
						if (eq <= 0) {
							throw new IllegalArgumentException("Columna sin '=' en: " + line);
						}
						values.put(parts[i].substring(0, eq).strip().toUpperCase(Locale.ROOT), parts[i].substring(eq + 1));
					}
					rows.add(new Row(parts[1].toUpperCase(Locale.ROOT), values));
				}
				default -> throw new IllegalArgumentException("Línea desconocida (se esperaba CALL, PARAM o ROW): " + line);
			}
		}
		return new ParsedCase(routine, params, rows);
	}

	/** La rutina que ejecuta el caso. */
	public static Routine routineOf(PlsqlModel model, ParsedCase parsed) {
		if (parsed.routine() == null) {
			return model.defaultRoutine();
		}
		return model.routine(parsed.routine())
			.orElseThrow(() -> new IllegalArgumentException("La rutina " + parsed.routine() + " no es pública"));
	}

	/** Comprueba un caso contra la firma y el esquema; devuelve el problema o null si es válido. */
	public static String validate(PlsqlModel model, ParsedCase parsed) {
		Routine routine;
		try {
			routine = routineOf(model, parsed);
		}
		catch (IllegalArgumentException ex) {
			return ex.getMessage();
		}
		for (String name : parsed.params().keySet()) {
			if (routine.params().stream().noneMatch(p -> p.name().equals(name) && p.isInput())) {
				return "El parámetro " + name + " no existe o no es de entrada en " + routine.shortName();
			}
		}
		for (Row row : parsed.rows()) {
			Table table = model.table(row.table()).orElse(null);
			if (table == null) {
				return "La tabla " + row.table() + " no está en el esquema";
			}
			for (Map.Entry<String, String> value : row.values().entrySet()) {
				Column column = table.column(value.getKey()).orElse(null);
				if (column == null) {
					return "La columna " + row.table() + "." + value.getKey() + " no existe";
				}
				String problem = literalProblem(column.family(), value.getValue());
				if (problem != null) {
					return row.table() + "." + value.getKey() + ": " + problem;
				}
			}
		}
		return null;
	}

	/** Crea el esquema y el programa, y lista los errores de compilación y los objetos creados. */
	public static String setupScript(String schemaDdl, String program) {
		String body = program.strip();
		if (!body.endsWith("/")) {
			body = body + "\n/";
		}
		return """
				SET DEFINE OFF
				SET SQLBLANKLINES ON
				SET FEEDBACK OFF HEADING OFF ECHO OFF VERIFY OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON TAB OFF
				WHENEVER SQLERROR CONTINUE
				%s

				%s

				SELECT 'COMPILE_ERR|' || name || '|' || line || '|' || position || '|' || REPLACE(text, CHR(10), ' ')
				  FROM user_errors ORDER BY name, sequence;
				SELECT 'OBJ|' || object_type || '|' || object_name || '|' || status
				  FROM user_objects WHERE object_type IN ('TABLE', 'PROCEDURE', 'FUNCTION', 'PACKAGE', 'PACKAGE BODY');
				EXIT
				""".formatted(schemaDdl.strip(), body);
	}

	/**
	 * Script de un caso: reinicia las secuencias, limpia las tablas (hijas primero), carga los datos
	 * (padres primero), invoca la rutina y vuelca los efectos sobre las tablas modificadas.
	 */
	public static String caseScript(PlsqlModel model, ParsedCase parsed) {
		Routine routine = routineOf(model, parsed);

		// En PL/SQL las variables se declaran antes que los subprogramas locales.
		StringBuilder declare = new StringBuilder();
		declare.append("  cp_inicio DATE := SYSDATE;\n");
		for (Param p : routine.outputs()) {
			declare.append("  ").append(variable(p)).append(' ').append(declaredType(p.type())).append(";\n");
		}
		if (routine.isFunction()) {
			declare.append("  cp_ret ").append(declaredType(routine.returnType())).append(";\n");
		}
		declare.append("""
				  FUNCTION cp_num(n NUMBER) RETURN VARCHAR2 IS
				  BEGIN
				    IF n IS NULL THEN RETURN '\\N'; END IF;
				    RETURN RTRIM(RTRIM(TO_CHAR(n, 'FM99999999999999999999999999990.99999999999999999999'), '0'), '.');
				  END;
				  FUNCTION cp_txt(s VARCHAR2) RETURN VARCHAR2 IS
				  BEGIN
				    IF s IS NULL THEN RETURN '\\N'; END IF;
				    IF s = USER THEN RETURN '%s'; END IF;
				    RETURN s;
				  END;
				  FUNCTION cp_dat(d DATE) RETURN VARCHAR2 IS
				  BEGIN
				    IF d IS NULL THEN RETURN '\\N'; END IF;
				    IF d BETWEEN cp_inicio - 2/86400 AND SYSDATE + 2/86400 THEN RETURN '%s'; END IF;
				    IF d = TRUNC(cp_inicio) THEN RETURN '%s'; END IF;
				    RETURN TO_CHAR(d, 'YYYY-MM-DD HH24:MI:SS');
				  END;
				  FUNCTION cp_bool(b BOOLEAN) RETURN VARCHAR2 IS
				  BEGIN
				    IF b IS NULL THEN RETURN '\\N'; END IF;
				    RETURN CASE WHEN b THEN 'TRUE' ELSE 'FALSE' END;
				  END;
				  FUNCTION cp_err RETURN VARCHAR2 IS
				  BEGIN
				    RETURN REGEXP_REPLACE(REGEXP_SUBSTR(SQLERRM, '^[^' || CHR(10) || ']*'), '^ORA-[0-9]+: ', '');
				  END;
				""".formatted(DB_USER, SYSTEM_TIME, SYSTEM_DAY));

		StringBuilder body = new StringBuilder();
		for (PlsqlModel.Sequence sequence : model.sequences()) {
			body.append("  BEGIN EXECUTE IMMEDIATE 'ALTER SEQUENCE ").append(sequence.name())
				.append(" RESTART START WITH ").append(sequence.start())
				.append("'; EXCEPTION WHEN OTHERS THEN NULL; END;\n");
		}
		List<String> parentsFirst = model.tablesInDependencyOrder();
		List<String> childrenFirst = new ArrayList<>(parentsFirst);
		java.util.Collections.reverse(childrenFirst);
		body.append("  FOR i IN 1 .. 3 LOOP\n");
		for (String table : childrenFirst) {
			body.append("    BEGIN DELETE FROM ").append(table).append("; EXCEPTION WHEN OTHERS THEN NULL; END;\n");
		}
		body.append("  END LOOP;\n");
		body.append("  BEGIN\n");
		for (String tableName : parentsFirst) {
			Table table = model.table(tableName).orElseThrow();
			for (Row row : parsed.rows()) {
				if (!row.table().equals(tableName)) {
					continue;
				}
				List<String> columns = new ArrayList<>(row.values().keySet());
				List<String> values = columns.stream()
					.map(c -> literal(table.column(c).orElseThrow().family(), row.values().get(c)))
					.toList();
				body.append("    INSERT INTO ").append(table.name()).append(" (").append(String.join(", ", columns))
					.append(") VALUES (").append(String.join(", ", values)).append(");\n");
			}
		}
		// Se confirma la preparación: una transacción autónoma de la rutina (por ejemplo, un log) no puede
		// esperar filas bloqueadas por esta sesión sin provocar un interbloqueo (ORA-00060).
		body.append("    COMMIT;\n");
		body.append("  EXCEPTION WHEN OTHERS THEN\n");
		body.append("    DBMS_OUTPUT.PUT_LINE('FIXTURE_ERR|' || SQLCODE || '|' || cp_err);\n");
		body.append("    ROLLBACK;\n");
		body.append("    RETURN;\n");
		body.append("  END;\n");

		body.append("  BEGIN\n");
		for (Param p : routine.outputs()) {
			if (p.isInput() && parsed.params().containsKey(p.name())) {
				body.append("    ").append(variable(p)).append(" := ")
					.append(literal(family(p.type(), model), parsed.params().get(p.name()))).append(";\n");
			}
		}
		List<String> args = new ArrayList<>();
		for (Param p : routine.params()) {
			if (p.isOutput()) {
				args.add(p.name() + " => " + variable(p));
			}
			else if (parsed.params().containsKey(p.name())) {
				args.add(p.name() + " => " + literal(family(p.type(), model), parsed.params().get(p.name())));
			}
			// Un parámetro de entrada omitido toma su valor DEFAULT (o NULL si no lo tiene).
		}
		body.append("    ").append(routine.isFunction() ? "cp_ret := " : "").append(routine.name());
		body.append(args.isEmpty() ? "" : "(" + String.join(", ", args) + ")").append(";\n");
		if (routine.isFunction()) {
			body.append("    DBMS_OUTPUT.PUT_LINE('RET|' || ").append(formatter(family(routine.returnType(), model), "cp_ret"))
				.append(");\n");
		}
		for (Param p : routine.outputs()) {
			body.append("    DBMS_OUTPUT.PUT_LINE('OUT|").append(p.name()).append("|' || ")
				.append(formatter(family(p.type(), model), variable(p))).append(");\n");
		}
		body.append("  EXCEPTION WHEN OTHERS THEN\n");
		body.append("    DBMS_OUTPUT.PUT_LINE('ERR|' || SQLCODE || '|' || cp_err);\n");
		body.append("  END;\n");

		for (String tableName : model.tablesWritten()) {
			Table table = model.table(tableName).orElse(null);
			if (table == null) {
				continue;
			}
			body.append("  FOR r IN (SELECT * FROM ").append(table.name()).append(") LOOP\n");
			StringBuilder line = new StringBuilder("'ROW|" + table.name() + "'");
			for (Column column : table.columns()) {
				line.append(" || '|").append(column.name()).append("=' || ")
					.append(formatter(column.family(), "r." + column.name()));
			}
			body.append("    DBMS_OUTPUT.PUT_LINE(").append(line).append(");\n");
			body.append("  END LOOP;\n");
		}
		body.append("  ROLLBACK;\n");

		return """
				SET DEFINE OFF
				SET SERVEROUTPUT ON SIZE UNLIMITED FORMAT WRAPPED
				SET FEEDBACK OFF HEADING OFF ECHO OFF VERIFY OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON TAB OFF
				DECLARE
				%sBEGIN
				%sEND;
				/
				EXIT
				""".formatted(declare, body);
	}

	// ------------------------------------------------------- utilidades

	private static String variable(Param p) {
		return "cp_" + p.name().toLowerCase(Locale.ROOT);
	}

	/** Tipo para declarar una variable en el bloque anónimo (sin restricciones de longitud en los textos). */
	private static String declaredType(String type) {
		if (type == null) {
			return "VARCHAR2(32767)";
		}
		if (type.toUpperCase(Locale.ROOT).contains("%")) {
			return type;
		}
		return switch (TypeFamily.of(type)) {
			case NUMBER -> "NUMBER";
			case TEXT -> "VARCHAR2(32767)";
			case DATE -> "DATE";
			case BOOLEAN -> "BOOLEAN";
			default -> type;
		};
	}

	private static TypeFamily family(String type, PlsqlModel model) {
		if (type == null) {
			return TypeFamily.TEXT;
		}
		String upper = type.toUpperCase(Locale.ROOT);
		if (upper.endsWith("%TYPE")) {
			String[] parts = upper.replace("%TYPE", "").split("\\.");
			if (parts.length == 2) {
				return model.table(parts[0]).flatMap(t -> t.column(parts[1])).map(Column::family).orElse(TypeFamily.TEXT);
			}
		}
		return TypeFamily.of(type);
	}

	private static String formatter(TypeFamily family, String expression) {
		return switch (family) {
			case NUMBER -> "cp_num(" + expression + ")";
			case DATE -> "cp_dat(" + expression + ")";
			case BOOLEAN -> "cp_bool(" + expression + ")";
			case TEXT -> "cp_txt(" + expression + ")";
			default -> "cp_txt(TO_CHAR(" + expression + "))";
		};
	}

	private static String literal(TypeFamily family, String value) {
		if (value == null || value.equals(NULL)) {
			return "NULL";
		}
		return switch (family) {
			case NUMBER -> value.strip();
			case DATE -> "TO_DATE('" + normalizeDate(value) + "', 'YYYY-MM-DD HH24:MI:SS')";
			case BOOLEAN -> value.strip().equalsIgnoreCase("TRUE") ? "TRUE" : "FALSE";
			default -> "'" + value.replace("'", "''") + "'";
		};
	}

	private static String literalProblem(TypeFamily family, String value) {
		if (value == null || value.equals(NULL)) {
			return null;
		}
		return switch (family) {
			case NUMBER -> value.strip().matches("-?\\d+(\\.\\d+)?") ? null : "número no válido «" + value + "»";
			case DATE -> normalizeDate(value).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}") ? null
					: "fecha no válida «" + value + "» (usa yyyy-MM-dd o yyyy-MM-dd HH:mm:ss)";
			default -> null;
		};
	}

	private static String normalizeDate(String value) {
		String v = value.strip();
		return v.matches("\\d{4}-\\d{2}-\\d{2}") ? v + " 00:00:00" : v;
	}

}
