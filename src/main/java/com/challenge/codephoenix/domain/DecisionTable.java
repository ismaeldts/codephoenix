package com.challenge.codephoenix.domain;

import java.util.List;

/** Tabla de decisión: combinaciones de condiciones y la acción resultante de una política de negocio. */
public record DecisionTable(String name, List<String> ruleIds, List<String> conditions, List<String> actions,
		List<Row> rows) {

	public record Row(List<String> conditions, List<String> actions) {
	}

}
