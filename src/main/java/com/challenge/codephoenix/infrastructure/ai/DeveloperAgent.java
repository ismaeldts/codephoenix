package com.challenge.codephoenix.infrastructure.ai;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.MigrationJob;

/** Genera el código moderno y su suite de pruebas, y los corrige a partir de la evidencia del sandbox. */
@Component
public class DeveloperAgent {

	public static final String CLI_CLASS = "adapter.in.cli.LegacyCliAdapter";

	private static final String SYSTEM = """
			Eres el Agente Desarrollador de un copiloto de modernización de código legacy bancario.
			Traduces lógica procedimental legacy a %s con Clean Code, SOLID, DDD y arquitectura hexagonal,
			manteniendo EQUIVALENCIA FUNCIONAL EXACTA: mismos cálculos, mismos redondeos y truncamientos, mismas
			validaciones en el mismo orden y la misma salida carácter por carácter.

			Base de conocimiento (obligatoria):
			%s

			Formato de respuesta: SOLO bloques de archivo, sin texto fuera de ellos:
			<<<FILE path=src/main/java/ruta/Archivo.java>>>
			...contenido completo del archivo...
			<<<END>>>
			""";

	private final AgentGateway gateway;
	private final KnowledgeBase knowledge;

	public DeveloperAgent(AgentGateway gateway, KnowledgeBase knowledge) {
		this.gateway = gateway;
		this.knowledge = knowledge;
	}

	public static String mainClass(String basePackage) {
		return basePackage + "." + CLI_CLASS;
	}

	public Map<String, String> generate(MigrationJob job, String goldenSamples, String ioContract) {
		String user = """
				Especificación aprobada (versión %d) — fuente de verdad:
				%s

				Arquitectura destino:
				- basePackage: %s
				- %s
				- Elementos: %s

				Código legacy de referencia (con número de línea):
				%s

				Comportamiento observado del legacy REAL (Golden Master, entrada -> salida):
				%s

				Genera el código de producción completo en src/main/java:
				1. Dominio puro (modelo, objetos de valor, servicios, specifications) sin E/S.
				2. Puertos y servicio de aplicación, y los adaptadores que indique la arquitectura.
				3. %s
				Comenta cada regla con su trazabilidad (`// Regla R-xx · %s:inicio-fin`).
				Respeta la complejidad máxima de 10 por método.
				""".formatted(job.getSpecVersion(), PromptSupport.rulesSpec(job.getAcceptedRules()),
				job.getArchitecture().basePackage(), job.getArchitecture().summary(), elements(job),
				PromptSupport.numbered(job.getMaskedCode()), goldenSamples, ioContract, job.getSourceName());
		return PromptSupport.parseFiles(gateway.text(job, "Desarrollador", AgentGateway.Profile.PROFUNDO, system(job), user));
	}

	public Map<String, String> fix(MigrationJob job, Map<String, String> currentFiles, String problem,
			String ioContract) {
		String user = """
				El código generado NO supera el quality gate. Evidencia del sandbox:
				%s

				Contrato de E/S que debe cumplir el adaptador de caracterización:
				%s

				Especificación aprobada:
				%s

				Código legacy de referencia (con número de línea):
				%s

				Código actual:
				%s

				Diagnostica la causa raíz comparando con la semántica exacta del legacy y la base de conocimiento.
				Devuelve SOLO los archivos que cambian, cada uno COMPLETO, en el formato de bloques.
				""".formatted(problem, ioContract, PromptSupport.rulesSpec(job.getAcceptedRules()),
				PromptSupport.numbered(job.getMaskedCode()), PromptSupport.filesAsBlocks(currentFiles));
		return PromptSupport.parseFiles(gateway.text(job, "Desarrollador (autocorrección)", AgentGateway.Profile.PROFUNDO, system(job), user));
	}

	/**
	 * Genera las pruebas de un lote de clases de producción. El código completo se
	 * entrega como contexto, pero solo se escriben pruebas para {@code targets},
	 * para que cada respuesta quepa en el presupuesto de salida.
	 */
	public Map<String, String> generateTests(MigrationJob job, Map<String, String> mainFiles, List<String> targets,
			String batchName, String goldenSamples) {
		String user = """
				Especificación aprobada:
				%s

				Código de producción completo (contexto):
				%s

				Resultados reales del legacy (Golden Master) para usar como valores esperados:
				%s

				Escribe en src/test/java (mismos paquetes) las pruebas SOLO para estas clases del lote "%s":
				%s

				- SOLO JUnit 5 Jupiter (org.junit.jupiter.api.*, org.junit.jupiter.params.*). Sin Mockito ni AssertJ;
				  si necesitas dobles de prueba, escríbelos a mano.
				- Aserciones explícitas con valores concretos tomados del Golden Master; nada de pruebas vacías.
				- Usa @DisplayName que empiece por el id de la regla que verifica (por ejemplo "R-03 ...").
				- Sé compacto: prefiere @ParameterizedTest con @CsvSource para los valores frontera y caminos de error.
				  Una clase de prueba por clase con lógica; omite records triviales sin comportamiento.
				- Cubre todas las ramas de esas clases: objetivo >= 85%% de líneas y >= 75%% de ramas.
				""".formatted(PromptSupport.rulesSpec(job.getAcceptedRules()), PromptSupport.filesAsBlocks(mainFiles),
				goldenSamples, batchName, String.join("\n", targets));
		Map<String, String> files = PromptSupport.parseFiles(gateway.text(job, "QA/Tester (pruebas " + batchName + ")",
				AgentGateway.Profile.CODIGO, system(job), user));
		files.keySet().removeIf(path -> !path.startsWith("src/test/java/"));
		return files;
	}

	public static final String FEATURES_DIR = "src/test/resources/features/";

	/** Escribe los escenarios Gherkin ejecutables (Cucumber) y sus definiciones de pasos. */
	public Map<String, String> generateBdd(MigrationJob job, Map<String, String> mainFiles, String goldenSamples,
			String gluePackage) {
		String user = """
				Especificación aprobada:
				%s

				Código de producción:
				%s

				Comportamiento real del legacy (Golden Master) — los valores esperados DEBEN salir de aquí:
				%s

				Escribe la especificación ejecutable BDD de esta migración:
				1. Archivos .feature en `%s`, en español: primera línea `# language: es`, palabras clave
				   Característica, Escenario, Esquema del escenario, Ejemplos, Dado, Cuando, Entonces, Y.
				   - Al menos UN escenario por cada regla aprobada, con la etiqueta de la regla en la línea anterior
				     (por ejemplo `@R-03`). Un escenario puede llevar varias etiquetas.
				   - Lenguaje de negocio comprensible para un auditor, con valores concretos y realistas.
				   - Los resultados esperados deben coincidir con el Golden Master o con el cálculo exacto del código.
				2. Definiciones de pasos en el paquete `%s` (src/test/java), con las anotaciones en español de
				   Cucumber 7 (`io.cucumber.java.es.Dado`, `Cuando`, `Entonces`, `Y`) y expresiones Cucumber
				   ({int}, {string}, {bigdecimal}...). Usa `org.junit.jupiter.api.Assertions` para las aserciones.
				   IMPORTANTE: con `# language: es`, Cucumber lee {bigdecimal} y {double} en formato español: la coma
				   es el separador decimal y el punto el de miles. Escribe los importes con coma decimal y sin
				   separador de miles (10661,10) o captúralos como {string} y conviértelos con new BigDecimal(...).
				   Compara importes con compareTo, no con equals (la escala puede diferir).
				   IMPORTANTE: Cucumber IGNORA la palabra clave al enlazar un paso con su definición. Define cada
				   texto de paso UNA sola vez, con una sola anotación (@Dado, @Cuando o @Entonces); un paso que en
				   el .feature empieza por "Y" usa esa misma definición. Nunca repitas el mismo texto con @Y ni en
				   dos métodos: Cucumber lanza DuplicateStepDefinitionException y fallan todos los escenarios.
				3. Los datos de cada escenario deben ser coherentes con su resultado: si tomas el resultado de un caso
				   del Golden Master, usa exactamente los VALORES DE LA ENTRADA decodificados de ese mismo caso.
				   Ejercita el servicio de aplicación o el dominio, no copies la lógica en los pasos. Sin Mockito.
				No modifiques el código de producción. Devuelve solo bloques de archivo.
				""".formatted(PromptSupport.rulesSpec(job.getAcceptedRules()), PromptSupport.filesAsBlocks(mainFiles),
				goldenSamples, FEATURES_DIR, gluePackage);
		return onlyBdd(PromptSupport.parseFiles(gateway.text(job, "QA/Tester (escenarios BDD)",
				AgentGateway.Profile.CODIGO, system(job), user)), gluePackage);
	}

	public Map<String, String> fixBdd(MigrationJob job, Map<String, String> allFiles, String problem,
			String gluePackage) {
		String user = """
				Los escenarios BDD no compilan, fallan o no cubren todas las reglas. Evidencia:
				%s

				Código actual (producción, pruebas, escenarios y pasos):
				%s

				El código de producción YA es equivalente al legacy (verificado con el Golden Master): corrige los
				.feature o las definiciones de pasos del paquete %s, no el código de producción. Si a un escenario le
				falta una regla, añádelo con su etiqueta. Devuelve SOLO los archivos que cambian, completos.
				""".formatted(problem, PromptSupport.filesAsBlocks(allFiles), gluePackage);
		return onlyBdd(PromptSupport.parseFiles(gateway.text(job, "QA/Tester (corrección BDD)",
				AgentGateway.Profile.CODIGO, system(job), user)), gluePackage);
	}

	private static Map<String, String> onlyBdd(Map<String, String> files, String gluePackage) {
		String glueDir = "src/test/java/" + gluePackage.replace('.', '/') + "/";
		files.keySet().removeIf(path -> !(path.startsWith(FEATURES_DIR) && path.endsWith(".feature"))
				&& !(path.startsWith(glueDir) && path.endsWith(".java")));
		return files;
	}

	public Map<String, String> fixTests(MigrationJob job, Map<String, String> allFiles, String problem) {
		String user = """
				La suite de pruebas no compila, falla o no alcanza la cobertura. Evidencia del sandbox:
				%s

				Código actual (producción y pruebas):
				%s

				El código de producción YA es equivalente al legacy (verificado con el Golden Master): corrige las
				PRUEBAS, no el código de producción. Devuelve SOLO los archivos de src/test/java que cambian, completos.
				""".formatted(problem, PromptSupport.filesAsBlocks(allFiles));
		Map<String, String> files = PromptSupport.parseFiles(gateway.text(job, "QA/Tester (corrección de pruebas)", AgentGateway.Profile.CODIGO,
				system(job), user));
		files.keySet().removeIf(path -> !path.startsWith("src/test/java/"));
		return files;
	}

	private String system(MigrationJob job) {
		return SYSTEM.formatted(job.getTargetStack(), knowledge.retrieve(knowledge.topicsFor(job.getLanguage())));
	}

	private static String elements(MigrationJob job) {
		return job.getArchitecture()
			.elements()
			.stream()
			.map(e -> e.name() + " (" + e.kind() + ", " + e.layer() + "): " + e.responsibility())
			.reduce((a, b) -> a + "\n  " + b)
			.orElse("-");
	}

}
