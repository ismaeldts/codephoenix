package com.challenge.codephoenix.domain;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Modelo de un programa PL/SQL obtenido por análisis estático: la rutina de
 * entrada con su firma, el esquema de tablas y qué tablas lee o modifica.
 * Es la base para generar el arnés de caracterización en Oracle.
 */
public record PlsqlModel(
		String entryPoint,
		String kind,
		String returnType,
		List<Param> params,
		List<Table> schema,
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

	public record Column(String name, String type, boolean nullable) {

		@JsonIgnore
		public TypeFamily family() {
			return TypeFamily.of(type);
		}

	}

	public record Table(String name, List<Column> columns) {

		public Optional<Column> column(String columnName) {
			return columns.stream().filter(c -> c.name().equalsIgnoreCase(columnName)).findFirst();
		}

	}

	/** Uso de una función no determinista (fecha del sistema, aleatorios, secuencias). */
	public record NonDeterminism(String construct, int line) {
	}

	public enum TypeFamily {

		NUMBER, TEXT, DATE, BOOLEAN, OTHER;

		public static TypeFamily of(String type) {
			String t = type == null ? "" : type.toUpperCase();
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

	@JsonIgnore
	public List<Param> outputs() {
		return params.stream().filter(Param::isOutput).toList();
	}

	@JsonIgnore
	public boolean isFunction() {
		return "FUNCTION".equals(kind);
	}

}
