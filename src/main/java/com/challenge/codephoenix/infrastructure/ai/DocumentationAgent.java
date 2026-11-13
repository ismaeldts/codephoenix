package com.challenge.codephoenix.infrastructure.ai;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.MigrationJob;

/** Redacta el informe ejecutivo de una página orientado a negocio. */
@Component
public class DocumentationAgent {

	private static final String SYSTEM = """
			Eres el Agente Documentador de un copiloto de modernización de código legacy bancario.
			Escribes para directivos de negocio y auditoría: claro, concreto y sin exagerar.
			Usas EXCLUSIVAMENTE las cifras que se te entregan; si un dato no está, no lo inventes. Respondes en
			español y en Markdown.
			""";

	private final AgentGateway gateway;

	public DocumentationAgent(AgentGateway gateway) {
		this.gateway = gateway;
	}

	public String executiveSummary(MigrationJob job, String metrics) {
		String user = """
				Programa migrado: %s (%s) — dominio: %s

				Explicación funcional:
				%s

				Métricas verificadas del proceso:
				%s

				Redacta un informe ejecutivo de UNA página (máximo 350 palabras) con las secciones:
				## Resumen
				## Reglas de negocio recuperadas
				## Riesgos mitigados
				## Evidencia de calidad
				## Ahorro estimado frente al análisis manual
				## Próximos pasos
				En "Ahorro estimado" deja claro que la estimación manual parte de los supuestos indicados.
				""".formatted(job.getSourceName(), job.getLanguage(), job.getDomainName(), job.getExplanation(), metrics);
		return gateway.text(job, "Documentador", AgentGateway.Profile.LIGERO, SYSTEM, user);
	}

}
