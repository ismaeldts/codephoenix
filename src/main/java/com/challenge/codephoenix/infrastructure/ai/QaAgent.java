package com.challenge.codephoenix.infrastructure.ai;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.MigrationJob;

/** Diseña los casos de caracterización (Golden Master) a partir de las reglas aprobadas. */
@Component
public class QaAgent {

	/**
	 * Caso propuesto. Según el lenguaje se usa una forma u otra:
	 * COBOL: {@code fields} (valores por campo del registro) o {@code input} literal;
	 * PL/SQL: {@code params} (parámetros de entrada) y {@code fixtures} (filas por tabla).
	 */
	public record CaseDraft(String description, String kind, Map<String, String> fields, String input,
			Map<String, String> params, Map<String, List<Map<String, String>>> fixtures, List<String> ruleIds) {
	}

	public record TestPlan(List<CaseDraft> cases) {
	}

	private static final String SYSTEM = """
			Eres el Agente QA/Tester de un copiloto de modernización de código legacy bancario y asegurador.
			Diseñas casos de prueba de caracterización: entradas que se ejecutarán contra el programa legacy REAL
			para capturar su comportamiento (Golden Master) y luego contra el código moderno para comparar.
			Usas solo datos sintéticos. Respondes en español.
			""";

	private final AgentGateway gateway;

	public QaAgent(AgentGateway gateway) {
		this.gateway = gateway;
	}

	public TestPlan plan(MigrationJob job, String formatInstructions) {
		String user = """
				Reglas aprobadas:
				%s

				Código fuente (con número de línea):
				%s

				Diseña entre 14 y 18 casos.
				%s
				- kind: NOMINAL, BORDE o ERROR.
				- Cubre todas las reglas al menos una vez (ruleIds), los valores frontera exactos de cada condición
				  (justo en el límite y justo fuera), cada rama de las políticas, los topes y los caminos de error.
				- description: qué valida el caso, con los valores en formato legible (por ejemplo 100.000,00).
				""".formatted(PromptSupport.rulesSpec(job.getAcceptedRules()), PromptSupport.numbered(job.getMaskedCode()),
				formatInstructions);
		return gateway.structured(job, "QA/Tester", AgentGateway.Profile.ESTANDAR, SYSTEM, user, TestPlan.class);
	}

}
