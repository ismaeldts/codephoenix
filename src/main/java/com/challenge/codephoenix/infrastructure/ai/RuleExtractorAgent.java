package com.challenge.codephoenix.infrastructure.ai;

import java.util.List;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.MigrationJob;

/** Descubre y formaliza las reglas de negocio con trazabilidad a las líneas de origen. */
@Component
public class RuleExtractorAgent {

	public record RuleDraft(String id, String name, String category, String description, String condition,
			String action, String gherkin, int sourceStartLine, int sourceEndLine, String sourceParagraph,
			double confidence, List<String> assumptions) {
	}

	public record RuleExtraction(String summary, List<RuleDraft> rules, List<String> infrastructureLogic) {
	}

	private static final String SYSTEM = """
			Eres el Agente Extractor de Reglas de un copiloto de modernización de código legacy bancario.
			Tu trabajo es convertir la "caja negra" del código en un catálogo de reglas de negocio explícitas,
			trazables y verificables por un auditor. Respondes en español.

			Principios:
			- Separa la lógica de negocio pura (validaciones, políticas, fórmulas, redondeos, constantes de negocio)
			  de la lógica de infraestructura (lectura y escritura de datos, formato de salida, archivos, mensajes).
			  Los efectos de negocio sobre los datos (qué estado o importe se guarda) SÍ son reglas.
			- Cada regla cita el rango EXACTO de líneas del código donde está implementada.
			- Haz explícitas las reglas tácitas: truncamientos o redondeos implícitos por el tipo del dato (PIC,
			  NUMBER(p,s)), uso o ausencia de ROUNDED, orden de las validaciones, comportamiento con NULL, errores por
			  datos inexistentes, ajustes del último periodo, condiciones que solo aplican a un tramo, etc.
			- La confianza (0.0 a 1.0) baja cuando la intención de negocio es ambigua o es una suposición tuya.
			- No inventes reglas que no estén en el código. El código muerto (unidades no invocadas) no es una regla
			  vigente: menciónalo en infrastructureLogic indicando que no se migra.
			- Los marcadores como [CONTRASENA_1] son datos enmascarados; ignóralos.
			""";

	private final AgentGateway gateway;
	private final KnowledgeBase knowledge;

	public RuleExtractorAgent(AgentGateway gateway, KnowledgeBase knowledge) {
		this.gateway = gateway;
		this.knowledge = knowledge;
	}

	public RuleExtraction extract(MigrationJob job, String unitTerm) {
		String user = """
				Base de conocimiento (semántica del lenguaje y glosario):
				%s

				Dominio funcional: %s

				Análisis estático determinista:
				%s

				Código fuente (con número de línea):
				%s

				Extrae el catálogo de reglas de negocio (entre 8 y 16 reglas, sé conciso):
				- id: R-01, R-02, ... en el orden en que se ejecutan.
				- name: máximo 8 palabras. description: máximo 2 frases.
				- category: una de VALIDACION, POLITICA, CALCULO, PRECISION, LIMITE.
				- condition / action: formato condición/acción, preciso y con los valores concretos del código.
				- gherkin: UN escenario en español de máximo 5 líneas (Escenario/Dado/Cuando/Entonces) con valores
				  de ejemplo concretos.
				- sourceStartLine / sourceEndLine: líneas exactas; sourceParagraph: nombre del %s que la contiene.
				- assumptions: máximo 2 supuestos o ambigüedades, una frase cada uno (lista vacía si no hay).
				En summary resume en 2 frases qué regula el programa.
				En infrastructureLogic lista la lógica de plataforma que NO es negocio (una frase por elemento, con líneas).
				""".formatted(knowledge.retrieve(knowledge.topicsFor(job.getLanguage())), job.getDomainName(),
				PromptSupport.analysisSummary(job.getAnalysis()),
				PromptSupport.numbered(job.getMaskedCode()), unitTerm);
		return gateway.structured(job, "Extractor de reglas", AgentGateway.Profile.ESTANDAR, SYSTEM, user, RuleExtraction.class);
	}

}
