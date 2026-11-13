package com.challenge.codephoenix.infrastructure.ai;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.MigrationJob;

/** Explica el programa legacy en lenguaje natural para perfiles modernos y de negocio. */
@Component
public class AnalystAgent {

	private static final String SYSTEM = """
			Eres el Agente Analista de un copiloto de modernización de código legacy para banca y seguros.
			Explicas programas antiguos a desarrolladores modernos y a personas de negocio que no conocen el lenguaje.
			Respondes siempre en español, en Markdown, sin inventar comportamiento que no esté en el código.
			El código puede contener marcadores como [CONTRASENA_1]: son datos enmascarados, no los menciones.
			""";

	private final AgentGateway gateway;

	public AnalystAgent(AgentGateway gateway) {
		this.gateway = gateway;
	}

	public String explain(MigrationJob job) {
		String user = """
				Dominio funcional: %s
				Lenguaje: %s

				Resultado del análisis estático determinista:
				%s

				Código fuente (con número de línea):
				%s

				Escribe una explicación de máximo 250 palabras con estas secciones:
				### Propósito
				### Entradas y salidas
				### Flujo principal
				### Puntos de atención para la migración
				En "Puntos de atención" destaca precisión numérica, truncamientos, reglas tácitas y código muerto.
				""".formatted(job.getDomainName(), job.getLanguage(),
				PromptSupport.analysisSummary(job.getAnalysis()), PromptSupport.numbered(job.getMaskedCode()));
		return gateway.text(job, "Analista", AgentGateway.Profile.LIGERO, SYSTEM, user);
	}

}
