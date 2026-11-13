package com.challenge.codephoenix.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Modelo de un programa PL/SQL obtenido por análisis estático: sus rutinas públicas con su firma,
 * el esquema de tablas con sus relaciones, las secuencias, los datos de referencia que carga el
 * artefacto y qué tablas lee o modifica. Es la base para generar el arnés de caracterización en Oracle.
 *
 * <p>{@code entryPoint}, {@code kind}, {@code returnType} y {@code params} describen la rutina por
 * defecto (la primera pública); {@code routines} las contiene todas.
 */
public record PlsqlModel(
		String entryPoint,
		String kind,
		String returnType,
		List<Param> params,
		List<Routine> routines,
		List<Table> schema,
		List<Sequence> sequences,
		List<SeedRow> seedRows,
		List<String> tablesRead,
		List<String> tablesWritten,
		List<String> missingTables,
		List<NonDeterminism> nonDeterministic) {

	public record Param(String name, String mode, String type) {

		@JsonIgnore
		public boolean isInput() {
			return mode.contains("IN");
		}

		@JsonIgnore
		public boolean isOutput() {
			return mode.contains("OUT");
		}

	}

	/** Rutina invocable desde fuera: un procedimiento o función independiente, o pública de un paquete. */
	public record Routine(String name, String kind, String returnType, List<Param> params) {

		@JsonIgnore
		public boolean isFunction() {
			return "FUNCTION".equals(kind);
		}

		@JsonIgnore
		public List<Param> outputs() {
			return params.stream().filter(Param::isOutput).toList();
		}

		@JsonIgnore
		public String shortName() {
			return name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
		}

	}

	public record Column(String name, String type, boolean nullable) {

		@JsonIgnore
		public TypeFamily family() {
			return TypeFamily.of(type);
		}

	}

	/** Tabla con sus columnas y las tablas a las que referencia por clave foránea. */
	public record Table(String name, List<Column> columns, List<String> references) {

		public Optional<Column> column(String columnName) {
			return columns.stream().filter(c -> c.name().equalsIgnoreCase(columnName)).findFirst();
		}

	}

	public record Sequence(String name, long start, long increment) {
	}

	/** Fila que el propio artefacto inserta (por ejemplo, parámetros de negocio); valores en formato de caso. */
	public record SeedRow(String table, Map<String, String> values) {
	}

	/** Uso de una función no determinista (fecha del sistema, aleatorios, secuencias). */
	public record NonDeterminism(String construct, int line) {
	}

	public enum TypeFamily {

		NUMBER, TEXT, DATE, BOOLEAN, OTHER;

		public static TypeFamily of(String type) {
			String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
			if (t.matches("(NUMBER|NUMERIC|DECIMAL|INTEGER|INT|SMALLINT|FLOAT|BINARY_INTEGER|PLS_INTEGER|NATURAL|"
					+ "POSITIVE|SIMPLE_INTEGER|BINARY_DOUBLE|BINARY_FLOAT)\\b.*")) {
				return NUMBER;
			}
			if (t.matches("(VARCHAR2|VARCHAR|NVARCHAR2|CHAR|NCHAR|CLOB|NCLOB|LONG)\\b.*")) {
				return TEXT;
			}
			if (t.matches("(DATE|TIMESTAMP)\\b.*")) {
				return DATE;
			}
			return t.startsWith("BOOLEAN") ? BOOLEAN : OTHER;
		}

	}

	public Optional<Table> table(String name) {
		return schema.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
	}

	/** Busca una rutina por nombre corto o calificado con el paquete. */
	public Optional<Routine> routine(String name) {
		if (name == null) {
			return Optional.empty();
		}
		String wanted = name.toUpperCase(Locale.ROOT);
		return routines.stream()
			.filter(r -> r.name().equals(wanted) || r.shortName().equals(wanted))
			.findFirst();
	}

	@JsonIgnore
	public Routine defaultRoutine() {
		return routines.isEmpty() ? new Routine(entryPoint, kind, returnType, params) : routines.getFirst();
	}

	@JsonIgnore
	public List<Param> outputs() {
		return params.stream().filter(Param::isOutput).toList();
	}

	@JsonIgnore
	public boolean isFunction() {
		return "FUNCTION".equals(kind);
	}

	/** Tablas ordenadas de padres a hijas según sus claves foráneas (las referencias desconocidas se ignoran). */
	@JsonIgnore
	public List<String> tablesInDependencyOrder() {
		Map<String, Table> byName = new LinkedHashMap<>();
		schema.forEach(t -> byName.put(t.name(), t));
		List<String> ordered = new ArrayList<>();
		Set<String> visiting = new LinkedHashSet<>();
		for (String name : byName.keySet()) {
			visit(name, byName, ordered, visiting);
		}
		return ordered;
	}

	private static void visit(String name, Map<String, Table> byName, List<String> ordered, Set<String> visiting) {
		if (ordered.contains(name) || !visiting.add(name)) {
			return; // ya ordenada, o ciclo de referencias: se rompe aquí
		}
		Table table = byName.get(name);
		for (String parent : table.references() == null ? List.<String>of() : table.references()) {
			if (byName.containsKey(parent) && !parent.equals(name)) {
				visit(parent, byName, ordered, visiting);
			}
		}
		visiting.remove(name);
		ordered.add(name);
	}

}
