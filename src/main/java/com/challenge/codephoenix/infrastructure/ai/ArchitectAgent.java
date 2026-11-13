package com.challenge.codephoenix.infrastructure.ai;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.ArchitectureDesign;
import com.challenge.codephoenix.domain.MigrationJob;

/** Diseña la arquitectura destino (hexagonal + DDD) a partir de la especificación aprobada. */
@Component
public class ArchitectAgent {

	private static final String SYSTEM = """
			Eres el Agente Arquitecto de un copiloto de modernización de código legacy bancario.
			Diseñas la arquitectura destino aplicando Domain-Driven Design y arquitectura hexagonal (puertos y
			adaptadores), con decisiones justificadas en ADRs. Respondes en español.

			Restricciones del stack destino:
			- %s, SIN dependencias externas en el código de producción (solo la biblioteca estándar del JDK).
			- La lógica de negocio vive en el dominio y no depende de E/S. Los importes usan BigDecimal con la
			  escala exacta del campo legacy. Las reglas complejas usan patrones (Specification, Strategy, Factory).
			%s
			""";

	private final AgentGateway gateway;

	public ArchitectAgent(AgentGateway gateway) {
		this.gateway = gateway;
	}

	public ArchitectureDesign design(MigrationJob job, String platformHint) {
		String user = """
				Dominio funcional: %s

				Especificación aprobada (versión %d):
				%s

				Análisis estático:
				%s

				Diseña la arquitectura destino:
				- basePackage: paquete Java en minúsculas, por ejemplo com.banco.creditos.amortizacion.
				- summary: 3-4 frases.
				- elements: entidades, objetos de valor, servicios de dominio, puertos y adaptadores. kind es uno de
				  ENTITY, VALUE_OBJECT, DOMAIN_SERVICE, SPECIFICATION, PORT_IN, APPLICATION_SERVICE, ADAPTER_IN.
				  layer es DOMINIO, APLICACION o ADAPTADOR. Indica qué reglas (R-xx) implementa cada uno.
				  Máximo 14 elementos; responsibility de máximo 30 palabras.
				- adrs: entre 3 y 5 ADRs (id ADR-001...) con contexto, decisión y consecuencias de máximo 3 frases
				  cada uno. Incluye uno sobre la precisión numérica (COMP-3 a BigDecimal, truncamiento vs. redondeo)
				  y otro sobre cómo se elimina el GO TO.
				- openApiYaml: contrato OpenAPI 3.0 en YAML, compacto, con UN endpoint POST de simulación, su request,
				  su respuesta (cuota, tabla y totales) y el error de validación con código E00-E04.
				- mermaidDiagram: diagrama Mermaid "flowchart LR" del hexágono (adaptadores, puertos, dominio).
				  Usa solo identificadores alfanuméricos y etiquetas entre corchetes, sin paréntesis en las etiquetas.
				""".formatted(job.getDomainName(), job.getSpecVersion(), PromptSupport.rulesSpec(job.getAcceptedRules()),
				PromptSupport.analysisSummary(job.getAnalysis()));
		return gateway.structured(job, "Arquitecto", AgentGateway.Profile.ESTANDAR, SYSTEM.formatted(job.getTargetStack(), platformHint), user,
				ArchitectureDesign.class);
	}

}
