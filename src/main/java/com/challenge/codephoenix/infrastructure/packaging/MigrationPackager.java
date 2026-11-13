package com.challenge.codephoenix.infrastructure.packaging;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.application.LegacyPlatform;
import com.challenge.codephoenix.domain.ArchitectureDesign;
import com.challenge.codephoenix.domain.BddReport;
import com.challenge.codephoenix.domain.DecisionTable;
import com.challenge.codephoenix.domain.SecurityReport;
import com.challenge.codephoenix.domain.BusinessRule;
import com.challenge.codephoenix.domain.CaseResult;
import com.challenge.codephoenix.domain.MigrationJob;
import com.challenge.codephoenix.domain.QualityReport;
import com.challenge.codephoenix.domain.TestRunReport;
import com.challenge.codephoenix.infrastructure.ai.DeveloperAgent;

/** Arma el paquete de migración descargable a partir del estado final del job. */
@Component
public class MigrationPackager {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
		.withZone(ZoneId.systemDefault());

	private final TraceabilityMatrix traceability;

	public MigrationPackager(TraceabilityMatrix traceability) {
		this.traceability = traceability;
	}

	public Path build(MigrationJob job, Path jobDir, LegacyPlatform platform) throws IOException {
		Path root = jobDir.resolve("package");
		deleteRecursively(root);
		Files.createDirectories(root);
		String mainClass = DeveloperAgent.mainClass(job.getArchitecture().basePackage());

		for (Map.Entry<String, String> file : job.getGeneratedFiles().entrySet()) {
			write(root.resolve(file.getKey()), file.getValue());
		}
		write(root.resolve("legacy").resolve(job.getSourceName()), job.getMaskedCode());
		String projectName = "migracion-" + job.getAnalysis().programId().toLowerCase().replaceAll("[^a-z0-9]+", "-");
		String glue = job.getArchitecture().basePackage() + ".bdd";
		write(root.resolve("Dockerfile"), dockerfile(projectName));
		write(root.resolve("build.gradle"), buildGradle(mainClass));
		write(root.resolve("settings.gradle"), "rootProject.name = '" + projectName + "'\n");
		write(root.resolve("src/test/java/" + glue.replace('.', '/') + "/RunCucumberTest.java"), cucumberRunner(glue));
		write(root.resolve("README.md"), readme(job, mainClass, platform));
		if (job.getLegacySchema() != null) {
			write(root.resolve("legacy/ESQUEMA.sql"), job.getLegacySchema() + "\n");
		}
		write(root.resolve("INFORME_EJECUTIVO.md"), job.getExecutiveSummary());
		write(root.resolve("docs/EXPLICACION_LEGACY.md"), "# Explicación del programa legacy\n\n" + job.getExplanation());
		write(root.resolve("docs/REGLAS_DE_NEGOCIO.md"), rulesCatalog(job));
		write(root.resolve("docs/TRAZABILIDAD.md"), traceabilityDoc(job));
		write(root.resolve("docs/ARQUITECTURA.md"), architectureDoc(job.getArchitecture()));
		write(root.resolve("docs/openapi.yaml"), job.getArchitecture().openApiYaml());
		for (ArchitectureDesign.Adr adr : job.getArchitecture().adrs()) {
			write(root.resolve("docs/adr/" + adr.id() + ".md"), """
					# %s: %s

					## Contexto
					%s

					## Decisión
					%s

					## Consecuencias
					%s
					""".formatted(adr.id(), adr.title(), adr.context(), adr.decision(), adr.consequences()));
		}
		write(root.resolve("reports/EQUIVALENCIA.md"), equivalenceDoc(job, platform));
		write(root.resolve("reports/CALIDAD.md"), qualityDoc(job.getQuality()));
		write(root.resolve("reports/PRUEBAS.md"), testsDoc(job.getTestRun(), job.getBdd()));
		write(root.resolve("reports/SEGURIDAD.md"), securityDoc(job));
		write(root.resolve("reports/AUDITORIA_IA.md"), auditDoc(job));
		copyDirectory(jobDir.resolve("reports/coverage-html"), root.resolve("reports/cobertura-html"));
		for (CaseResult result : job.getEquivalence().cases()) {
			write(root.resolve("reports/golden-master/" + result.id() + ".in"), result.input() + "\n");
			write(root.resolve("reports/golden-master/" + result.id() + ".legacy.out"),
					String.join("\n", result.legacyOutput()) + "\n");
		}

		Path zip = jobDir.resolve("paquete-migracion-" + job.getId() + ".zip");
		zip(root, zip);
		return zip;
	}

	private static String dockerfile(String projectName) {
		return """
				# Compila, ejecuta las pruebas unitarias y los escenarios Gherkin (la construcción falla si alguno
				# falla) y deja lista la aplicación.
				FROM gradle:8.14-jdk21 AS build
				WORKDIR /app
				COPY settings.gradle build.gradle ./
				COPY src ./src
				RUN gradle --no-daemon -q test installDist

				FROM eclipse-temurin:21-jre
				COPY --from=build /app/build/install/%s /app
				ENTRYPOINT ["/app/bin/%s"]
				""".formatted(projectName, projectName);
	}

	/** Lanzador JUnit Platform de los escenarios Gherkin, para ejecutarlos con `gradle test`. */
	private static String cucumberRunner(String gluePackage) {
		return """
				package %s;

				import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

				import org.junit.platform.suite.api.ConfigurationParameter;
				import org.junit.platform.suite.api.IncludeEngines;
				import org.junit.platform.suite.api.SelectClasspathResource;
				import org.junit.platform.suite.api.Suite;

				/** Ejecuta los escenarios de src/test/resources/features con Cucumber. */
				@Suite
				@IncludeEngines("cucumber")
				@SelectClasspathResource("features")
				@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "%s")
				public class RunCucumberTest {
				}
				""".formatted(gluePackage, gluePackage);
	}

	private static String buildGradle(String mainClass) {
		return """
				plugins {
					id 'application'
				}

				java {
					toolchain {
						languageVersion = JavaLanguageVersion.of(21)
					}
				}

				repositories {
					mavenCentral()
				}

				dependencies {
					testImplementation platform('org.junit:junit-bom:5.11.4')
					testImplementation 'org.junit.jupiter:junit-jupiter'
					testImplementation 'org.junit.platform:junit-platform-suite'
					testImplementation 'io.cucumber:cucumber-java:7.20.1'
					testImplementation 'io.cucumber:cucumber-junit-platform-engine:7.20.1'
					testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
				}

				application {
					mainClass = '%s'
				}

				tasks.named('test') {
					useJUnitPlatform()
				}
				""".formatted(mainClass);
	}

	private static String readme(MigrationJob job, String mainClass, LegacyPlatform platform) {
		return """
				# Migración de %s — %s

				Paquete generado por **CodePhoenix**, copiloto de modernización de código legacy.

				| Indicador | Resultado |
				|---|---|
				| Reglas de negocio aprobadas | %d (especificación v%d) |
				| Equivalencia legacy vs. moderno | %d/%d casos idénticos |
				| Pruebas unitarias | %d/%d exitosas |
				| Escenarios BDD (Gherkin ejecutable) | %s |
				| Cobertura | %.1f%% líneas / %.1f%% ramas |
				| Hallazgos bloqueantes / críticos | %d / %d |
				| Vulnerabilidades críticas (SAST) | %s |

				## Ejecutar con un solo comando

				```bash
				%s
				```

				La construcción de la imagen compila el código y ejecuta las pruebas unitarias y los escenarios
				Gherkin; si alguno falla, la imagen no se construye. Alternativa sin Docker: `gradle test run`.

				> En Windows usa Git Bash o `cmd`: Windows PowerShell 5.1 antepone una marca BOM al texto que
				> envía por tubería y la entrada llega con un carácter extra (igual que le ocurriría al original).

				Clase de entrada: `%s` (lee la entrada con el formato del programa legacy y produce la misma salida).

				## Contenido

				| Ruta | Contenido |
				|---|---|
				| `src/main/java` | Código moderno (arquitectura hexagonal) |
				| `src/test/java` | Pruebas unitarias JUnit 5 y pasos de Cucumber |
				| `src/test/resources/features` | Especificación ejecutable: escenarios Gherkin etiquetados por regla |
				| `legacy/` | Programa original (con datos sensibles enmascarados) y, si aplica, su esquema |
				| `docs/REGLAS_DE_NEGOCIO.md` | Catálogo de reglas aprobado, con escenarios Gherkin |
				| `docs/TRAZABILIDAD.md` | Matriz regla → líneas legacy → código moderno |
				| `docs/ARQUITECTURA.md`, `docs/adr/`, `docs/openapi.yaml` | Diseño, decisiones y contrato REST |
				| `reports/` | Equivalencia, calidad, seguridad, pruebas, cobertura, auditoría de IA y Golden Master |
				| `INFORME_EJECUTIVO.md` | Resumen de una página para negocio |
				""".formatted(job.getSourceName(), job.getDomainName(), job.getAcceptedRules().size(),
				job.getSpecVersion(), job.getEquivalence().passed(), job.getEquivalence().total(),
				job.getTestRun().succeeded(), job.getTestRun().testsFound(),
				job.getBdd() == null ? "no ejecutados" : job.getBdd().passed() + "/" + job.getBdd().scenarios() + " aprobados",
				job.getTestRun().lineCoverage(), job.getTestRun().branchCoverage(), job.getQuality().blockers(),
				job.getQuality().criticals(),
				job.getSecurity() == null || !job.getSecurity().executed() ? "no ejecutado"
						: job.getSecurity().critical() + " (" + job.getSecurity().tool() + ")",
				platform.sampleRunCommand(job), mainClass);
	}

	private static String rulesCatalog(MigrationJob job) {
		StringBuilder sb = new StringBuilder("# Catálogo de reglas de negocio\n\n");
		sb.append("Especificación versión ").append(job.getSpecVersion()).append(" — ").append(job.getRulesSummary())
			.append("\n\n");
		for (BusinessRule r : job.getRules()) {
			sb.append("## ").append(r.getId()).append(" · ").append(r.getName()).append("\n\n");
			sb.append("| Campo | Valor |\n|---|---|\n");
			sb.append("| Estado | ").append(r.getStatus()).append(" |\n");
			sb.append("| Categoría | ").append(r.getCategory()).append(" |\n");
			sb.append("| Origen | `").append(job.getSourceName()).append(':').append(r.getSourceStartLine()).append('-')
				.append(r.getSourceEndLine()).append("` (").append(r.getSourceParagraph()).append(") |\n");
			sb.append("| Confianza | ").append(Math.round(r.getConfidence() * 100)).append("% |\n");
			sb.append("| Condición | ").append(cell(r.getCondition())).append(" |\n");
			sb.append("| Acción | ").append(cell(r.getAction())).append(" |\n");
			if (r.getReviewerComment() != null && !r.getReviewerComment().isBlank()) {
				sb.append("| Comentario del revisor | ").append(cell(r.getReviewerComment())).append(" |\n");
			}
			sb.append('\n').append(r.getDescription()).append("\n\n");
			if (!r.getAssumptions().isEmpty()) {
				sb.append("**Supuestos:**\n");
				r.getAssumptions().forEach(a -> sb.append("- ").append(a).append('\n'));
				sb.append('\n');
			}
			sb.append("```gherkin\n").append(r.getGherkin()).append("\n```\n\n");
		}
		if (!job.getDecisionTables().isEmpty()) {
			sb.append("## Tablas de decisión\n\n");
			for (DecisionTable table : job.getDecisionTables()) {
				sb.append("### ").append(table.name()).append("\n\n");
				if (table.ruleIds() != null && !table.ruleIds().isEmpty()) {
					sb.append("Reglas: ").append(String.join(", ", table.ruleIds())).append("\n\n");
				}
				List<String> headers = new ArrayList<>();
				table.conditions().forEach(c -> headers.add("¿" + c + "?"));
				table.actions().forEach(a -> headers.add("→ " + a));
				sb.append("| ").append(String.join(" | ", headers.stream().map(MigrationPackager::cell).toList())).append(" |\n");
				sb.append("|").append("---|".repeat(headers.size())).append('\n');
				for (DecisionTable.Row row : table.rows()) {
					List<String> cells = new ArrayList<>();
					cells.addAll(row.conditions() == null ? List.of() : row.conditions());
					cells.addAll(row.actions() == null ? List.of() : row.actions());
					sb.append("| ").append(String.join(" | ", cells.stream().map(MigrationPackager::cell).toList())).append(" |\n");
				}
				sb.append('\n');
			}
		}
		sb.append("## Lógica de infraestructura (no es regla de negocio)\n\n");
		job.getInfrastructureLogic().forEach(i -> sb.append("- ").append(i).append('\n'));
		return sb.toString();
	}

	private String traceabilityDoc(MigrationJob job) {
		List<TraceabilityMatrix.Row> rows = traceability.build(job.getAcceptedRules(), job.getGeneratedFiles());
		StringBuilder sb = new StringBuilder("# Matriz de trazabilidad\n\n");
		sb.append(traceability.tracedRules(rows)).append(" de ").append(rows.size())
			.append(" reglas aprobadas enlazadas con el código moderno.\n\n");
		sb.append("| Regla | Origen legacy | Código moderno |\n|---|---|---|\n");
		for (TraceabilityMatrix.Row row : rows) {
			String modern = row.links().isEmpty() ? "**sin enlace**"
					: String.join("<br>", row.links().stream()
						.map(l -> "`" + l.file().replaceFirst("^src/main/java/", "") + ":" + l.line() + "` " + l.element())
						.toList());
			sb.append("| ").append(row.rule().getId()).append(' ').append(cell(row.rule().getName())).append(" | `")
				.append(job.getSourceName()).append(':').append(row.rule().getSourceStartLine()).append('-')
				.append(row.rule().getSourceEndLine()).append("` | ").append(modern).append(" |\n");
		}
		return sb.toString();
	}

	private static String architectureDoc(ArchitectureDesign design) {
		StringBuilder sb = new StringBuilder("# Arquitectura destino\n\n");
		sb.append(design.summary()).append("\n\nPaquete base: `").append(design.basePackage()).append("`\n\n");
		sb.append("```mermaid\n").append(design.mermaidDiagram()).append("\n```\n\n");
		sb.append("| Elemento | Tipo | Capa | Responsabilidad |\n|---|---|---|---|\n");
		design.elements()
			.forEach(e -> sb.append("| ").append(e.name()).append(" | ").append(e.kind()).append(" | ")
				.append(e.layer()).append(" | ").append(cell(e.responsibility())).append(" |\n"));
		sb.append("\n## Decisiones de arquitectura\n\n");
		design.adrs().forEach(a -> sb.append("- [").append(a.id()).append(": ").append(a.title()).append("](adr/")
			.append(a.id()).append(".md)\n"));
		return sb.toString();
	}

	private static String equivalenceDoc(MigrationJob job, LegacyPlatform platform) {
		var eq = job.getEquivalence();
		StringBuilder sb = new StringBuilder("# Reporte de equivalencia funcional\n\n");
		sb.append("Método: Golden Master. Cada entrada se ejecutó en el programa original (")
			.append(platform.runtimeDescription())
			.append(") y en el código moderno; se comparó la salida línea a línea y campo a campo.\n\n");
		sb.append("**Resultado: ").append(eq.passed()).append('/').append(eq.total())
			.append(" casos idénticos** (intento de generación ").append(eq.attempt()).append(").\n\n");
		sb.append("| Caso | Tipo | Descripción | Entrada | Líneas | Resultado |\n|---|---|---|---|---|---|\n");
		for (CaseResult c : eq.cases()) {
			sb.append("| ").append(c.id()).append(" | ").append(c.kind()).append(" | ").append(cell(c.description()))
				.append(" | `").append(c.input()).append("` | ").append(c.legacyOutput().size()).append(" | ")
				.append(c.passed() ? "✅ idéntico" : "❌ " + cell(String.join("; ", c.differences()))).append(" |\n");
		}
		return sb.toString();
	}

	private static String qualityDoc(QualityReport quality) {
		StringBuilder sb = new StringBuilder("# Reporte de calidad estática\n\n");
		sb.append("| Métrica | Valor | Umbral |\n|---|---|---|\n");
		sb.append("| Archivos analizados | ").append(quality.filesAnalyzed()).append(" | - |\n");
		sb.append("| Líneas de código | ").append(quality.linesOfCode()).append(" | - |\n");
		sb.append("| Hallazgos bloqueantes | ").append(quality.blockers()).append(" | 0 |\n");
		sb.append("| Hallazgos críticos | ").append(quality.criticals()).append(" | 0 |\n");
		sb.append("| Complejidad ciclomática máxima por método | ").append(quality.maxMethodComplexity())
			.append(" | ≤ 10 |\n\n");
		sb.append("Reglas verificadas: precisión decimal (sin double/float en el núcleo), BigDecimal desde cadena, ")
			.append("dependencias hexagonales, secretos en código, catch vacíos, printStackTrace, complejidad.\n\n");
		if (quality.findings().isEmpty()) {
			sb.append("Sin hallazgos.\n");
		}
		else {
			sb.append("| Severidad | Regla | Archivo | Línea | Mensaje |\n|---|---|---|---|---|\n");
			quality.findings()
				.forEach(f -> sb.append("| ").append(f.severity()).append(" | ").append(f.rule()).append(" | `")
					.append(f.file()).append("` | ").append(f.line()).append(" | ").append(cell(f.message()))
					.append(" |\n"));
		}
		return sb.toString();
	}

	private static String testsDoc(TestRunReport tests, BddReport bdd) {
		String bddSection = bdd == null ? "\n## Escenarios BDD\n\nNo se ejecutaron.\n" : """

				## Escenarios BDD (Gherkin ejecutable con Cucumber)

				| Métrica | Valor |
				|---|---|
				| Escenarios | %d |
				| Aprobados | %d |
				| Fallidos | %d |
				| Reglas cubiertas por escenarios etiquetados | %d (%s) |
				| Reglas sin escenario | %s |

				Los escenarios están en `src/test/resources/features`; cada uno lleva la etiqueta de la regla que
				verifica (por ejemplo `@R-03`). Se ejecutan con `gradle test` mediante `RunCucumberTest`.

				```
				%s
				```
				""".formatted(bdd.scenarios(), bdd.passed(), bdd.failed(), bdd.coveredRules().size(),
				String.join(", ", bdd.coveredRules()), bdd.uncoveredRules().isEmpty() ? "ninguna"
						: String.join(", ", bdd.uncoveredRules()), bdd.output().strip());
		return testsDocBase(tests) + bddSection;
	}

	private static String securityDoc(MigrationJob job) {
		SecurityReport s = job.getSecurity();
		StringBuilder sb = new StringBuilder("# Reporte de seguridad\n\n");
		sb.append("## 1. Protección de datos antes de usar IA\n\n");
		sb.append(job.getMaskingFindings().size())
			.append(" datos sensibles (credenciales, cadenas de conexión, correos, IPs, tarjetas) se enmascararon antes ")
			.append("de enviar el código a los modelos. Solo se usaron datos sintéticos.\n\n");
		sb.append("## 2. Análisis estático de seguridad (SAST)\n\n");
		if (s == null || !s.executed()) {
			sb.append("No se ejecutó.\n\n");
		}
		else {
			sb.append("Herramienta: **").append(s.tool()).append("**, sobre el bytecode compilado, dentro del sandbox ")
				.append("sin red. Las vulnerabilidades de prioridad alta o media y los errores de corrección de prioridad ")
				.append("alta son críticos y bloquean el quality gate.\n\n");
			sb.append("| Severidad | Hallazgos |\n|---|---|\n");
			sb.append("| Críticos | ").append(s.critical()).append(" |\n");
			sb.append("| Mayores | ").append(s.major()).append(" |\n");
			sb.append("| Menores | ").append(s.minor()).append(" |\n\n");
			if (!s.findings().isEmpty()) {
				sb.append("| Severidad | Regla | Archivo | Línea | Descripción |\n|---|---|---|---|---|\n");
				s.findings()
					.forEach(f -> sb.append("| ").append(f.severity()).append(" | ").append(cell(f.rule())).append(" | `")
						.append(f.file()).append("` | ").append(f.line()).append(" | ").append(cell(f.message()))
						.append(" |\n"));
				sb.append('\n');
			}
		}
		sb.append("## 3. Dependencias\n\n");
		List<String> deps = s == null ? List.of() : s.productionDependencies();
		sb.append(deps.isEmpty()
				? "El código de producción no usa librerías de terceros: solo la biblioteca estándar de Java 21. No hay "
						+ "dependencias de producción que puedan contener vulnerabilidades conocidas (CVE). Las pruebas usan "
						+ "JUnit 5 y Cucumber, que no forman parte de la imagen final.\n\n"
				: "Dependencias de terceros en producción (revisar contra bases de CVE): " + String.join(", ", deps) + "\n\n");
		sb.append("## 4. Secretos en el código generado\n\n");
		long secrets = job.getQuality() == null ? 0
				: job.getQuality().findings().stream().filter(f -> f.rule().equals("secreto-en-codigo")).count();
		sb.append(secrets == 0 ? "0 secretos detectados en el código generado.\n" : secrets + " posibles secretos detectados.\n");
		return sb.toString();
	}

	private static String testsDocBase(TestRunReport tests) {
		return """
				# Reporte de pruebas

				| Métrica | Valor | Umbral |
				|---|---|---|
				| Pruebas encontradas | %d | - |
				| Exitosas | %d | 100%% |
				| Fallidas | %d | 0 |
				| Cobertura de líneas | %.1f%% | ≥ 80%% |
				| Cobertura de ramas | %.1f%% | ≥ 70%% |

				El detalle navegable de la cobertura está en `cobertura-html/index.html`.

				```
				%s
				```
				""".formatted(tests.testsFound(), tests.succeeded(), tests.failed(), tests.lineCoverage(),
				tests.branchCoverage(), tests.output().strip());
	}

	private static String auditDoc(MigrationJob job) {
		StringBuilder sb = new StringBuilder("# Auditoría de uso de IA\n\n");
		sb.append("Todo el código enviado al modelo se enmascaró previamente (")
			.append(job.getMaskingFindings().size())
			.append(" datos sensibles detectados). Se registra el hash SHA-256 de cada prompt, no su contenido.\n\n");
		sb.append("| Fecha | Agente | Modelo | Hash del prompt | Tokens entrada | Tokens salida | Duración | Resultado |\n");
		sb.append("|---|---|---|---|---|---|---|---|\n");
		job.getAudit()
			.forEach(a -> sb.append("| ").append(TIMESTAMP.format(a.at())).append(" | ").append(a.agent()).append(" | ")
				.append(a.model()).append(" | `").append(a.promptSha256(), 0, 16).append("…` | ").append(a.inputTokens())
				.append(" | ").append(a.outputTokens()).append(" | ").append(a.durationMs() / 1000.0).append(" s | ")
				.append(a.outcome()).append(" |\n"));
		return sb.toString();
	}

	private static String cell(String text) {
		return text == null ? "-" : text.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
	}

	private static void copyDirectory(Path source, Path target) throws IOException {
		if (!Files.isDirectory(source)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(source)) {
			for (Path path : paths.toList()) {
				Path dest = target.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(dest);
				}
				else {
					Files.createDirectories(dest.getParent());
					Files.copy(path, dest);
				}
			}
		}
	}

	private static void zip(Path root, Path zipFile) throws IOException {
		try (OutputStream out = Files.newOutputStream(zipFile); ZipOutputStream zip = new ZipOutputStream(out);
				Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile).toList()) {
				zip.putNextEntry(new ZipEntry(root.relativize(path).toString().replace('\\', '/')));
				Files.copy(path, zip);
				zip.closeEntry();
			}
		}
	}

	static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				Files.delete(path);
			}
		}
	}

}
