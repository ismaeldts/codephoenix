package com.challenge.codephoenix.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.challenge.codephoenix.config.CodephoenixProperties;
import com.challenge.codephoenix.domain.ArchitectureDesign;
import com.challenge.codephoenix.domain.BddReport;
import com.challenge.codephoenix.domain.BusinessRule;
import com.challenge.codephoenix.domain.CaseResult;
import com.challenge.codephoenix.domain.EquivalenceReport;
import com.challenge.codephoenix.domain.MigrationJob;
import com.challenge.codephoenix.domain.Phase;
import com.challenge.codephoenix.domain.PhaseState;
import com.challenge.codephoenix.domain.QualityReport;
import com.challenge.codephoenix.domain.SecurityReport;
import com.challenge.codephoenix.domain.StaticAnalysis;
import com.challenge.codephoenix.domain.TestCase;
import com.challenge.codephoenix.domain.TestRunReport;
import com.challenge.codephoenix.infrastructure.ai.AgentException;
import com.challenge.codephoenix.infrastructure.ai.AnalystAgent;
import com.challenge.codephoenix.infrastructure.ai.ArchitectAgent;
import com.challenge.codephoenix.infrastructure.ai.DeveloperAgent;
import com.challenge.codephoenix.infrastructure.ai.DocumentationAgent;
import com.challenge.codephoenix.infrastructure.ai.QaAgent;
import com.challenge.codephoenix.infrastructure.ai.RuleExtractorAgent;
import com.challenge.codephoenix.infrastructure.analysis.JavaQualityAnalyzer;
import com.challenge.codephoenix.infrastructure.analysis.LanguageDetector;
import com.challenge.codephoenix.infrastructure.analysis.SecretMasker;
import com.challenge.codephoenix.infrastructure.analysis.SecurityAnalyzer;
import com.challenge.codephoenix.infrastructure.analysis.StepDefinitionLinter;
import com.challenge.codephoenix.infrastructure.packaging.MigrationPackager;
import com.challenge.codephoenix.infrastructure.packaging.TraceabilityMatrix;
import com.challenge.codephoenix.infrastructure.sandbox.DockerSandbox;
import com.challenge.codephoenix.infrastructure.sandbox.ExecutionService;

/**
 * Orquestador con estado del flujo de 8 fases. Cada fase produce un artefacto
 * y solo se avanza si supera su quality gate. Entre la fase 3 y la 5 el flujo
 * se detiene hasta la aprobación humana de las reglas.
 */
@Service
public class MigrationOrchestrator {

	private static final Logger log = LoggerFactory.getLogger(MigrationOrchestrator.class);

	private static final int MAX_TEST_FIX_ATTEMPTS = 2;
	private static final double MIN_LINE_COVERAGE = 80.0;
	private static final double MIN_BRANCH_COVERAGE = 70.0;

	private final JobRepository repository;
	private final CodephoenixProperties properties;
	private final SecretMasker masker;
	private final LanguageDetector languageDetector;
	private final List<LegacyPlatform> platforms;
	private final JavaQualityAnalyzer qualityAnalyzer;
	private final DockerSandbox sandbox;
	private final ExecutionService execution;
	private final AnalystAgent analyst;
	private final RuleExtractorAgent ruleExtractor;
	private final ArchitectAgent architect;
	private final QaAgent qa;
	private final DeveloperAgent developer;
	private final DocumentationAgent documenter;
	private final MigrationPackager packager;
	private final TraceabilityMatrix traceability;
	private final SecurityAnalyzer securityAnalyzer;
	private final StepDefinitionLinter stepLinter;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public MigrationOrchestrator(JobRepository repository, CodephoenixProperties properties, SecretMasker masker,
			LanguageDetector languageDetector, List<LegacyPlatform> platforms, JavaQualityAnalyzer qualityAnalyzer,
			DockerSandbox sandbox, ExecutionService execution, AnalystAgent analyst, RuleExtractorAgent ruleExtractor,
			ArchitectAgent architect, QaAgent qa, DeveloperAgent developer, DocumentationAgent documenter,
			MigrationPackager packager, TraceabilityMatrix traceability, SecurityAnalyzer securityAnalyzer,
			StepDefinitionLinter stepLinter) {
		this.securityAnalyzer = securityAnalyzer;
		this.stepLinter = stepLinter;
		this.repository = repository;
		this.properties = properties;
		this.masker = masker;
		this.languageDetector = languageDetector;
		this.platforms = platforms;
		this.qualityAnalyzer = qualityAnalyzer;
		this.sandbox = sandbox;
		this.execution = execution;
		this.analyst = analyst;
		this.ruleExtractor = ruleExtractor;
		this.architect = architect;
		this.qa = qa;
		this.developer = developer;
		this.documenter = documenter;
		this.packager = packager;
		this.traceability = traceability;
	}

	// ------------------------------------------------------------------ API

	public MigrationJob start(String sourceName, String code, String domainName, String targetStack,
			List<String> caseInputs) {
		if (code == null || code.isBlank()) {
			throw new InvalidRequestException("El artefacto está vacío. Carga un archivo de código o usa el ejemplo.");
		}
		if (code.length() > 200_000) {
			throw new InvalidRequestException("El artefacto supera los 200.000 caracteres admitidos en la demo.");
		}
		String safeName = sanitizeFileName(sourceName);
		MigrationJob job = new MigrationJob(UUID.randomUUID().toString().substring(0, 8), safeName,
				blankToDefault(domainName, "Créditos de consumo"), blankToDefault(targetStack, "Java 21"), code,
				caseInputs);
		repository.save(job);
		executor.submit(() -> runDiscovery(job));
		return job;
	}

	public BusinessRule decide(String jobId, String ruleId, String decision, String description, String condition,
			String action, String comment) {
		MigrationJob job = requireJob(jobId);
		if (job.getStatus() != MigrationJob.Status.ESPERANDO_APROBACION) {
			throw new InvalidRequestException("Las reglas solo se pueden revisar mientras se espera la aprobación.");
		}
		BusinessRule rule = job.rule(ruleId)
			.orElseThrow(() -> new InvalidRequestException("No existe la regla " + ruleId));
		synchronized (job) {
			switch (decision == null ? "" : decision.toUpperCase()) {
				case "APROBAR" -> rule.approve(comment);
				case "AJUSTAR" -> rule.adjust(description, condition, action, comment);
				case "RECHAZAR" -> {
					if (comment == null || comment.isBlank()) {
						throw new InvalidRequestException("Indica el motivo del rechazo para la auditoría.");
					}
					rule.reject(comment);
				}
				default -> throw new InvalidRequestException("Decisión no válida: usa APROBAR, AJUSTAR o RECHAZAR.");
			}
		}
		job.log(Phase.VALIDACION, "INFO", ruleId + " → " + rule.getStatus()
				+ (comment == null || comment.isBlank() ? "" : " (" + comment + ")"));
		return rule;
	}

	public void approve(String jobId) {
		MigrationJob job = requireJob(jobId);
		if (job.getStatus() != MigrationJob.Status.ESPERANDO_APROBACION) {
			throw new InvalidRequestException("La especificación no está pendiente de aprobación.");
		}
		long pending = job.getRules().stream().filter(r -> !r.isDecided()).count();
		if (pending > 0) {
			throw new InvalidRequestException("Faltan " + pending + " reglas por revisar antes de aprobar.");
		}
		if (job.getAcceptedRules().isEmpty()) {
			throw new InvalidRequestException("Debe aprobarse al menos una regla para generar código.");
		}
		job.specificationApproved();
		long adjusted = job.getRules().stream().filter(r -> r.getStatus() == BusinessRule.Status.AJUSTADA).count();
		long rejected = job.getRules().stream().filter(r -> r.getStatus() == BusinessRule.Status.RECHAZADA).count();
		job.phase(Phase.VALIDACION)
			.complete(true, "Especificación v" + job.getSpecVersion() + " aprobada: "
					+ (job.getAcceptedRules().size() - adjusted) + " aprobadas, " + adjusted + " ajustadas, "
					+ rejected + " rechazadas");
		job.log(Phase.VALIDACION, "OK", "Especificación v" + job.getSpecVersion() + " aprobada por el ingeniero");
		executor.submit(() -> runDelivery(job));
	}

	public MigrationJob requireJob(String jobId) {
		return repository.find(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
	}

	// ------------------------------------------------------------ flujos

	private void runDiscovery(MigrationJob job) {
		try {
			ingest(job);
			analyze(job);
			extractRules(job);
		}
		catch (Exception ex) {
			abort(job, ex);
		}
	}

	private void runDelivery(MigrationJob job) {
		try {
			design(job);
			verify(job, generate(job));
			deliver(job);
		}
		catch (Exception ex) {
			abort(job, ex);
		}
	}

	// ------------------------------------------------------------- fases

	private void ingest(MigrationJob job) throws IOException, InterruptedException {
		PhaseState phase = begin(job, Phase.INGESTA, "Identificando lenguaje y enmascarando datos sensibles");
		String language = languageDetector.detect(job.getOriginalCode());
		LegacyPlatform platform = platforms.stream()
			.filter(p -> p.language().equals(language))
			.findFirst()
			.orElseThrow(() -> new GateFailedException(Phase.INGESTA, "DESCONOCIDO".equals(language)
					? "No se reconoce el lenguaje del artefacto. Carga un programa COBOL o PL/SQL."
					: "Lenguaje detectado: " + language + ". Esta versión ejecuta el Golden Master para "
							+ platforms.stream().map(LegacyPlatform::language).collect(Collectors.joining(" y "))
							+ "; carga un programa en uno de esos lenguajes o usa un ejemplo incluido."));
		SecretMasker.Result masked = masker.mask(job.getOriginalCode());
		job.ingested(language, masked.maskedCode(), masked.findings());
		masked.findings()
			.forEach(f -> job.log(Phase.INGESTA, "WARN",
					"Línea " + f.line() + ": " + f.type() + " enmascarado como " + f.replacement()));
		int residual = masker.mask(masked.maskedCode()).findings().size();

		Path legacyFile = jobDir(job).resolve("legacy").resolve(job.getSourceName());
		Files.createDirectories(legacyFile.getParent());
		Files.writeString(legacyFile, job.getOriginalCode(), StandardCharsets.UTF_8);

		phase.progress("Verificando que el artefacto se puede ejecutar en el sandbox");
		if (!sandbox.isAvailable()) {
			throw new GateFailedException(Phase.INGESTA, "El sandbox de ejecución no está disponible. Inicia Docker "
					+ "Desktop y construye la imagen con: docker build -t " + properties.sandbox().image() + " sandbox");
		}
		String verification = platform.verifyArtifact(job, jobDir(job));		pass(job, phase, residual == 0, verification + "; " + masked.findings().size()
				+ " datos sensibles enmascarados; " + residual + " secretos residuales en el flujo hacia la IA");
	}

	private void analyze(MigrationJob job) throws IOException, InterruptedException {
		PhaseState phase = begin(job, Phase.ANALISIS, "Construyendo grafo de dependencias y métricas");
		LegacyPlatform platform = platform(job);
		StaticAnalysis analysis = platform.analyze(job);
		job.analyzed(analysis);
		job.log(Phase.ANALISIS, "INFO", analysis.paragraphs().size() + " unidades (" + platform.unitTerm() + "), "
				+ analysis.callGraph().size() + " dependencias, complejidad total " + analysis.totalComplexity() + ", "
				+ analysis.goToCount() + " saltos GO TO");
		analysis.deadParagraphs().forEach(p -> job.log(Phase.ANALISIS, "WARN", "Código muerto detectado: " + p));
		List<String> unresolved = platform.unresolvedDependencies(analysis);
		if (!unresolved.isEmpty()) {
			throw new GateFailedException(Phase.ANALISIS, "Dependencias sin resolver: " + String.join(", ", unresolved)
					+ ". Incluye su código en el artefacto.");
		}
		phase.progress("Preparando la ejecución del programa original");
		platform.prepareExecution(job, jobDir(job));
		analysis = job.getAnalysis();
		phase.progress("El Agente Analista explica el programa en lenguaje natural");
		job.explained(analyst.explain(job));
		pass(job, phase, true, "100% inventariado: " + analysis.paragraphs().size() + " unidades, "
				+ analysis.callGraph().size() + " dependencias, " + analysis.deadParagraphs().size() + " con código muerto");
	}

	private void extractRules(MigrationJob job) {
		PhaseState phase = begin(job, Phase.REGLAS, "El Agente Extractor formaliza las reglas de negocio");
		RuleExtractorAgent.RuleExtraction extraction = ruleExtractor.extract(job, platform(job).unitTerm());
		if (extraction == null || extraction.rules() == null || extraction.rules().isEmpty()) {
			throw new GateFailedException(Phase.REGLAS, "El modelo no devolvió reglas de negocio.");
		}
		int totalLines = job.getAnalysis().totalLines();
		List<BusinessRule> rules = new ArrayList<>();
		Set<String> ids = new LinkedHashSet<>();
		int invalid = 0;
		for (RuleExtractorAgent.RuleDraft d : extraction.rules()) {
			String id = d.id() != null && d.id().matches("R-\\d{2,3}") && ids.add(d.id()) ? d.id()
					: "R-%02d".formatted(rules.size() + 1);
			ids.add(id);
			boolean validLines = d.sourceStartLine() >= 1 && d.sourceEndLine() <= totalLines
					&& d.sourceStartLine() <= d.sourceEndLine();
			List<String> assumptions = new ArrayList<>(d.assumptions() == null ? List.of() : d.assumptions());
			double confidence = Math.max(0, Math.min(1, d.confidence()));
			if (!validLines) {
				invalid++;
				assumptions.add("Las líneas de origen citadas por el modelo no son válidas; revisar manualmente.");
				confidence = Math.min(confidence, 0.3);
			}
			rules.add(new BusinessRule(id, d.name(), d.category(), d.description(), d.condition(), d.action(),
					d.gherkin(), d.sourceStartLine(), d.sourceEndLine(), d.sourceParagraph(), confidence, assumptions));
		}
		job.decisionTablesExtracted(extraction.decisionTables() == null ? List.of() : extraction.decisionTables().stream()
			.filter(t -> t.rows() != null && !t.rows().isEmpty() && t.conditions() != null && t.actions() != null)
			.toList());
		job.rulesExtracted(extraction.summary(), rules,
				extraction.infrastructureLogic() == null ? List.of() : extraction.infrastructureLogic());
		long lowConfidence = rules.stream().filter(r -> r.getConfidence() < 0.8).count();
		pass(job, phase, invalid == 0, rules.size() + " reglas y " + job.getDecisionTables().size()
				+ " tablas de decisión; " + (rules.size() - invalid)
				+ " con trazabilidad válida a líneas de origen; " + lowConfidence + " con confianza < 80% requieren atención");
		job.phase(Phase.VALIDACION).start("Esperando la revisión del ingeniero");
		job.phase(Phase.VALIDACION).awaitApproval("Revisa cada regla: aprobar, ajustar o rechazar");
		job.log(Phase.VALIDACION, "INFO", "Flujo en pausa: se requiere aprobación humana de " + rules.size() + " reglas");
	}

	private void design(MigrationJob job) {
		PhaseState phase = begin(job, Phase.ARQUITECTURA, "El Agente Arquitecto diseña el dominio y los puertos");
		ArchitectureDesign design = architect.design(job, platform(job).architectureHint(job));
		String basePackage = design.basePackage() != null
				&& design.basePackage().matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+") ? design.basePackage()
						: "com.codephoenix.migracion";
		design = new ArchitectureDesign(basePackage, design.summary(), safe(design.elements()), safe(design.adrs()),
				design.openApiYaml(), design.mermaidDiagram());
		job.designed(design);
		boolean hasDomain = design.elements().stream().anyMatch(e -> "DOMINIO".equalsIgnoreCase(e.layer()));
		boolean hasPorts = design.elements().stream().anyMatch(e -> "PORT_IN".equalsIgnoreCase(e.kind()));
		boolean hasAdapters = design.elements().stream().anyMatch(e -> "ADAPTADOR".equalsIgnoreCase(e.layer()));
		boolean ok = hasDomain && hasPorts && hasAdapters && !design.adrs().isEmpty() && design.openApiYaml() != null;
		pass(job, phase, ok, design.elements().size() + " elementos (dominio, puertos y adaptadores), "
				+ design.adrs().size() + " ADRs y contrato OpenAPI");
	}

	/** Fase 6: captura el Golden Master, genera el código y lo autocorrige hasta compilar sin hallazgos críticos. */
	private Characterization generate(MigrationJob job) throws IOException, InterruptedException {
		PhaseState phase = begin(job, Phase.GENERACION, "El Agente QA diseña los casos de caracterización");
		Path dir = jobDir(job);
		LegacyPlatform platform = platform(job);
		QaAgent.TestPlan plan = qa.plan(job, platform.caseInstructions(job));
		List<TestCase> cases = platform.toTestCases(job, plan.cases() == null ? List.of() : plan.cases());
		if (cases.isEmpty()) {
			throw new GateFailedException(Phase.GENERACION, "No se pudieron diseñar casos de caracterización válidos.");
		}
		job.log(Phase.GENERACION, "INFO", cases.size() + " casos de caracterización diseñados");

		phase.progress("Capturando el Golden Master: ejecutando el programa original (" + platform.runtimeDescription() + ")");
		ExecutionService.RunOutcome legacy = platform.runLegacy(job, dir, cases);
		if (!legacy.ok()) {
			throw new GateFailedException(Phase.GENERACION, "El programa legacy no se pudo ejecutar:\n" + tail(legacy.log(), 1500));
		}
		Map<String, List<String>> golden = new LinkedHashMap<>(legacy.outputs());
		List<TestCase> runnable = new ArrayList<>();
		for (TestCase testCase : cases) {
			List<String> output = golden.getOrDefault(testCase.id(), List.of());
			if (output.stream().anyMatch(l -> l.startsWith("FIXTURE_ERR"))) {
				job.log(Phase.GENERACION, "WARN", "Caso " + testCase.id() + " descartado: sus datos iniciales no cumplen el esquema ("
						+ output.getFirst() + ")");
				golden.remove(testCase.id());
			}
			else {
				runnable.add(testCase);
			}
		}
		cases = runnable;
		job.testCasesDesigned(cases);
		job.log(Phase.GENERACION, "OK", "Golden Master capturado del programa original para " + golden.size() + " casos");
		phase.progress("El Agente Desarrollador genera el código (intento 1)");
		Map<String, String> files = onlySources(developer.generate(job, goldenSamples(job, cases, golden, 6),
				platform.ioContract(job)));
		if (files.isEmpty()) {
			throw new GateFailedException(Phase.GENERACION, "El Agente Desarrollador no devolvió archivos de código.");
		}
		applyFiles(job, files);

		int max = Math.max(1, properties.maxCorrectionAttempts());
		for (int attempt = 1; ; attempt++) {
			phase.progress("Compilando en el sandbox y aplicando el quality gate (intento " + attempt + ")");
			ExecutionService.RunOutcome modern = execution.runModern(dir, mainClass(job), cases);
			String problem;
			if (!modern.ok()) {
				problem = "ERRORES DE COMPILACIÓN (javac):\n" + tail(modern.log(), 6000);
				job.log(Phase.GENERACION, "WARN", "Intento " + attempt + ": el código no compila");
			}
			else {
				QualityReport quality = qualityAnalyzer.analyze(job.getGeneratedFiles());
				phase.progress("Análisis de seguridad SAST (SpotBugs + FindSecBugs) en el sandbox (intento " + attempt + ")");
				SecurityReport security = securityScan(job, dir);
				List<QualityReport.Finding> severe = Stream.concat(quality.findings().stream(), security.findings().stream())
					.filter(f -> f.severity().equals("BLOQUEANTE") || f.severity().equals("CRITICO"))
					.toList();
				if (severe.isEmpty() && security.executed()) {
					job.tested(null, quality);
					pass(job, phase, true, "Compilación exitosa en el intento " + attempt + "; "
							+ quality.filesAnalyzed() + " archivos, 0 hallazgos bloqueantes o críticos (calidad y SAST)");
					return new Characterization(golden, modern.outputs());
				}
				if (!security.executed()) {
					throw new GateFailedException(Phase.GENERACION, "El análisis SAST no se pudo ejecutar:\n"
							+ tail(security.output(), 1500));
				}
				problem = "HALLAZGOS CRÍTICOS DEL ANÁLISIS ESTÁTICO Y DE SEGURIDAD:\n" + severe.stream()
					.map(f -> "- [" + f.severity() + "] " + f.file() + ":" + f.line() + " " + f.message())
					.collect(Collectors.joining("\n"));
				job.log(Phase.GENERACION, "WARN", "Intento " + attempt + ": " + severe.size() + " hallazgos críticos");
			}
			if (attempt >= max) {
				throw new GateFailedException(Phase.GENERACION, "No se superó el quality gate tras " + attempt
						+ " intentos:\n" + tail(problem, 1500));
			}
			phase.progress("Autocorrección: el Agente Desarrollador corrige el código (intento " + (attempt + 1) + ")");
			applyFiles(job, onlySources(developer.fix(job, mainFiles(job), problem, platform.ioContract(job))));
		}
	}

	/** Salidas del legacy (Golden Master) y del código moderno para los mismos casos. */
	private record Characterization(Map<String, List<String>> golden, Map<String, List<String>> modern) {
	}

	/** Fase 7: equivalencia contra el Golden Master, pruebas unitarias, cobertura y calidad. */
	private void verify(MigrationJob job, Characterization characterization) throws IOException, InterruptedException {
		PhaseState phase = begin(job, Phase.VERIFICACION, "Comparando legacy vs. moderno caso por caso");
		Path dir = jobDir(job);
		List<TestCase> cases = job.getTestCases();
		Map<String, List<String>> golden = characterization.golden();
		Map<String, List<String>> modern = characterization.modern();
		int max = Math.max(1, properties.maxCorrectionAttempts());
		for (int attempt = 1; ; attempt++) {
			EquivalenceReport report = EquivalenceComparator.compare(cases, golden, modern, job.getGenerationAttempts());
			job.verified(report);
			job.log(Phase.VERIFICACION, report.allPassed() ? "OK" : "WARN",
					"Equivalencia: " + report.passed() + "/" + report.total() + " casos idénticos");
			if (report.allPassed()) {
				break;
			}
			if (attempt >= max) {
				throw new GateFailedException(Phase.VERIFICACION, "Sin equivalencia funcional tras " + attempt
						+ " intentos: " + report.failed() + " casos con diferencias.");
			}
			phase.progress("Autocorrección por diferencias de equivalencia (intento " + (attempt + 1) + ")");
			String contract = platform(job).ioContract(job);
			applyFiles(job, onlySources(developer.fix(job, mainFiles(job), equivalenceProblem(report), contract)));
			ExecutionService.RunOutcome rerun = execution.runModern(dir, mainClass(job), cases);
			if (!rerun.ok()) {
				job.log(Phase.VERIFICACION, "WARN", "La corrección no compila; se reintenta");
				applyFiles(job, onlySources(developer.fix(job, mainFiles(job),
						"ERRORES DE COMPILACIÓN (javac):\n" + tail(rerun.log(), 6000), contract)));
				rerun = execution.runModern(dir, mainClass(job), cases);
			}
			modern = rerun.ok() ? rerun.outputs() : Map.of();
		}

		phase.progress("El Agente QA genera las pruebas unitarias y los escenarios BDD ejecutables");
		String samples = goldenSamples(job, cases, golden, 10);
		String glue = job.getArchitecture().basePackage() + ".bdd";
		var bddFuture = executor.submit(() -> developer.generateBdd(job, mainFiles(job), samples, glue));
		generateTestsInBatches(job, samples);
		applyFiles(job, await(bddFuture));

		TestRunReport tests = null;
		BddReport bdd = null;
		for (int attempt = 0; attempt <= MAX_TEST_FIX_ATTEMPTS; attempt++) {
			phase.progress("Ejecutando JUnit 5 + JaCoCo y Cucumber en el sandbox" + (attempt > 0 ? " (corrección " + attempt + ")" : ""));
			StepDefinitionLinter.Result lint = stepLinter.removeDuplicateSteps(job.getGeneratedFiles(),
					"src/test/java/" + glue.replace('.', '/') + "/");
			if (!lint.files().isEmpty()) {
				applyFiles(job, lint.files());
				job.log(Phase.VERIFICACION, "WARN", "Pasos de Cucumber duplicados corregidos automáticamente (Cucumber "
						+ "ignora la palabra clave Dado/Cuando/Entonces/Y): " + String.join(", ", lint.removed()));
			}
			tests = execution.runUnitTests(dir);
			bdd = tests.compiled() ? bddReport(job, execution.runBdd(dir, glue)) : null;
			String testProblem = testProblem(tests);
			String bddProblem = tests.compiled() ? bddProblem(bdd) : null;
			if (testProblem == null && bddProblem == null) {
				break;
			}
			if (testProblem != null) {
				job.log(Phase.VERIFICACION, "WARN", "Pruebas: " + testProblem.lines().findFirst().orElse(""));
			}
			if (bddProblem != null) {
				job.log(Phase.VERIFICACION, "WARN", "BDD: " + bddProblem.lines().findFirst().orElse(""));
			}
			if (attempt == MAX_TEST_FIX_ATTEMPTS) {
				break;
			}
			if (testProblem != null) {
				applyFiles(job, onlySources(developer.fixTests(job, job.getGeneratedFiles(), testProblem)));
			}
			if (bddProblem != null) {
				applyFiles(job, developer.fixBdd(job, job.getGeneratedFiles(), bddProblem, glue));
			}
		}
		job.bddVerified(bdd);

		phase.progress("Análisis final de calidad y seguridad (SAST)");
		QualityReport quality = qualityAnalyzer.analyze(job.getGeneratedFiles());
		SecurityReport security = securityScan(job, dir);
		job.tested(tests, quality);
		job.log(Phase.VERIFICACION, "INFO", tests.succeeded() + "/" + tests.testsFound() + " pruebas exitosas; cobertura "
				+ tests.lineCoverage() + "% líneas / " + tests.branchCoverage() + "% ramas");
		if (bdd != null) {
			job.log(Phase.VERIFICACION, bdd.allPassed() ? "OK" : "WARN", "BDD: " + bdd.passed() + "/" + bdd.scenarios()
					+ " escenarios Gherkin aprobados; " + bdd.coveredRules().size() + " reglas cubiertas");
		}
		job.log(Phase.VERIFICACION, security.critical() == 0 ? "OK" : "WARN", "SAST: " + security.critical()
				+ " críticos, " + security.major() + " mayores, " + security.minor() + " menores; dependencias de producción: "
				+ (security.productionDependencies().isEmpty() ? "ninguna" : security.productionDependencies()));

		boolean ok = job.getEquivalence().allPassed() && testProblem(tests) == null && bdd != null
				&& bddProblem(bdd) == null && security.executed() && security.critical() == 0 && quality.blockers() == 0
				&& quality.criticals() == 0 && quality.maxMethodComplexity() <= JavaQualityAnalyzer.MAX_METHOD_COMPLEXITY;
		String summary = "Equivalencia " + job.getEquivalence().passed() + "/" + job.getEquivalence().total()
				+ "; pruebas " + tests.succeeded() + "/" + tests.testsFound() + "; BDD "
				+ (bdd == null ? "no ejecutado" : bdd.passed() + "/" + bdd.scenarios() + " escenarios") + "; cobertura "
				+ tests.lineCoverage() + "% líneas / " + tests.branchCoverage() + "% ramas; complejidad máx. "
				+ quality.maxMethodComplexity() + "; " + quality.blockers() + " bloqueantes, " + quality.criticals()
				+ " críticos; SAST " + security.critical() + " vulnerabilidades críticas";
		if (!ok) {
			throw new GateFailedException(Phase.VERIFICACION, summary);
		}
		pass(job, phase, true, summary);
	}

	/**
	 * Divide las clases de producción en lotes (dominio por un lado, aplicación y
	 * adaptadores por otro, como máximo 8 clases por lote) y genera sus pruebas
	 * en paralelo para que cada respuesta del modelo quepa en su presupuesto.
	 */
	private void generateTestsInBatches(MigrationJob job, String samples) throws IOException {
		Map<String, String> main = mainFiles(job);
		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (String path : main.keySet()) {
			String layer = path.contains("/domain/") ? "dominio" : "aplicación y adaptadores";
			groups.computeIfAbsent(layer, k -> new ArrayList<>()).add(path);
		}
		List<java.util.concurrent.Future<Map<String, String>>> futures = new ArrayList<>();
		for (Map.Entry<String, List<String>> group : groups.entrySet()) {
			List<String> paths = group.getValue();
			int batches = (paths.size() + 7) / 8;
			for (int b = 0; b < batches; b++) {
				List<String> targets = paths.subList(b * paths.size() / batches, (b + 1) * paths.size() / batches);
				String name = group.getKey() + (batches > 1 ? " " + (b + 1) : "");
				futures.add(executor.submit(() -> developer.generateTests(job, main, targets, name, samples)));
			}
		}
		Map<String, String> tests = new LinkedHashMap<>();
		for (java.util.concurrent.Future<Map<String, String>> future : futures) {
			try {
				tests.putAll(future.get());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
			catch (java.util.concurrent.ExecutionException ex) {
				throw ex.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex.getCause());
			}
		}
		applyFiles(job, onlySources(tests));
	}

	private void deliver(MigrationJob job) throws IOException {
		PhaseState phase = begin(job, Phase.EMPAQUETADO, "El Agente Documentador redacta el informe ejecutivo");
		String summary = documenter.executiveSummary(job, metrics(job));
		job.packaged(null, summary);
		phase.progress("Armando el paquete: código, pruebas, documentación, reportes y contenedor");
		Path zip = packager.build(job, jobDir(job), platform(job));
		job.packaged(zip, summary);
		pass(job, phase, Files.exists(zip), "Paquete listo (" + Files.size(zip) / 1024 + " KB) con Dockerfile, "
				+ "README, matriz de trazabilidad, reportes e informe ejecutivo");
		job.finish();
		job.log(null, "OK", "Migración completada en " + job.getElapsedMs() / 1000 + " s");
	}

	// --------------------------------------------------------- utilidades

	private String goldenSamples(MigrationJob job, List<TestCase> cases, Map<String, List<String>> golden, int max) {
		LegacyPlatform platform = platform(job);
		List<TestCase> selected = new ArrayList<>();
		for (String kind : List.of("NOMINAL", "BORDE", "ERROR", "USUARIO")) {
			cases.stream().filter(c -> kind.equals(c.kind())).limit(Math.max(1, max / 3)).forEach(selected::add);
		}
		return selected.stream().distinct().limit(max).map(c -> {
			List<String> out = golden.getOrDefault(c.id(), List.of());
			List<String> shown = out.size() <= 8 ? out
					: Stream.of(out.subList(0, 5), List.of("... (" + (out.size() - 7) + " líneas omitidas) ..."),
							out.subList(out.size() - 2, out.size())).flatMap(List::stream).toList();
			String decoded = platform.describeInput(job, c.input());
			return "Caso " + c.id() + " (" + c.kind() + "): " + c.description() + "\nENTRADA: «" + c.input() + "»"
					+ (decoded.isEmpty() ? "" : "\nVALORES DE LA ENTRADA (decodificados, úsalos tal cual): " + decoded)
					+ "\nSALIDA LEGACY:\n" + String.join("\n", shown);
		}).collect(Collectors.joining("\n\n"));
	}

	private static String equivalenceProblem(EquivalenceReport report) {
		return "DIFERENCIAS DE EQUIVALENCIA FUNCIONAL (legacy real vs. código moderno). Casos fallidos: "
				+ report.failed() + "/" + report.total() + "\n" + report.cases()
					.stream()
					.filter(c -> !c.passed())
					.limit(6)
					.map(MigrationOrchestrator::describeFailure)
					.collect(Collectors.joining("\n\n"));
	}

	private static String describeFailure(CaseResult c) {
		String modern = c.modernOutput().isEmpty() ? "(sin salida)"
				: String.join("\n", c.modernOutput().subList(0, Math.min(3, c.modernOutput().size())));
		return "Caso " + c.id() + " — " + c.description() + "\nENTRADA: «" + c.input() + "»\n"
				+ String.join("\n", c.differences()) + "\nPrimeras líneas del moderno:\n" + modern;
	}

	private static String testProblem(TestRunReport tests) {
		if (!tests.compiled()) {
			return "Las pruebas no compilan:\n" + tail(tests.output(), 5000);
		}
		if (tests.testsFound() == 0) {
			return "No se encontraron pruebas JUnit 5 ejecutables.\n" + tail(tests.output(), 3000);
		}
		if (tests.failed() > 0) {
			return tests.failed() + " pruebas fallan:\n" + tail(tests.output(), 5000);
		}
		if (tests.lineCoverage() < MIN_LINE_COVERAGE || tests.branchCoverage() < MIN_BRANCH_COVERAGE) {
			return "Cobertura insuficiente: " + tests.lineCoverage() + "% líneas (mínimo " + MIN_LINE_COVERAGE
					+ "%) y " + tests.branchCoverage() + "% ramas (mínimo " + MIN_BRANCH_COVERAGE
					+ "%). Agrega pruebas para los caminos no cubiertos.";
		}
		return null;
	}

	private String metrics(MigrationJob job) {
		StaticAnalysis a = job.getAnalysis();
		long automatedMs = job.getPhases()
			.stream()
			.filter(p -> p.getPhase() != Phase.VALIDACION && p.getDurationMs() != null)
			.mapToLong(PhaseState::getDurationMs)
			.sum();
		double manualHours = Math.ceil(a.codeLines() / (double) properties.manualEstimate().locPerHour());
		long rejected = job.getRules().stream().filter(r -> r.getStatus() == BusinessRule.Status.RECHAZADA).count();
		long traced = traceability.tracedRules(traceability.build(job.getAcceptedRules(), job.getGeneratedFiles()));
		return """
				- Líneas de código legacy: %d (%d párrafos, %d dependencias, %d GO TO, código muerto: %s)
				- Datos sensibles enmascarados antes de usar IA: %d
				- Reglas de negocio: %d descubiertas, %d aprobadas o ajustadas, %d rechazadas por el ingeniero
				- Reglas trazadas al código moderno: %d de %d
				- Equivalencia funcional contra el programa original ejecutado en %s: %d/%d casos idénticos
				- Pruebas unitarias: %d/%d exitosas; cobertura %.1f%% líneas y %.1f%% ramas
				- Calidad: %d bloqueantes, %d críticos, complejidad máxima por método %d
				- Intentos de generación con autocorrección: %d
				- Tiempo automatizado de extremo a extremo: %.1f minutos (sin contar la revisión humana)
				- Estimación manual (supuesto: %d líneas analizadas, migradas y probadas por hora, US$%d/hora): %.0f horas, US$%.0f
				- Tokens de IA consumidos: %d de entrada, %d de salida
				""".formatted(a.codeLines(), a.paragraphs().size(), a.callGraph().size(), a.goToCount(),
				a.deadParagraphs().isEmpty() ? "ninguno" : String.join(", ", a.deadParagraphs()),
				job.getMaskingFindings().size(), job.getRules().size(), job.getAcceptedRules().size(), rejected,
				traced, job.getAcceptedRules().size(), platform(job).runtimeDescription(), job.getEquivalence().passed(),
				job.getEquivalence().total(),
				job.getTestRun().succeeded(), job.getTestRun().testsFound(), job.getTestRun().lineCoverage(),
				job.getTestRun().branchCoverage(), job.getQuality().blockers(), job.getQuality().criticals(),
				job.getQuality().maxMethodComplexity(), job.getGenerationAttempts(), automatedMs / 60000.0,
				properties.manualEstimate().locPerHour(), properties.manualEstimate().hourlyRateUsd(), manualHours,
				manualHours * properties.manualEstimate().hourlyRateUsd(), job.getTotalInputTokens(),
				job.getTotalOutputTokens());
	}

	private void applyFiles(MigrationJob job, Map<String, String> files) throws IOException {
		job.codeGenerated(files);
		Path root = jobDir(job).resolve("generated");
		if (Files.exists(root)) {
			try (Stream<Path> paths = Files.walk(root)) {
				for (Path p : paths.sorted((x, y) -> y.getNameCount() - x.getNameCount()).toList()) {
					Files.delete(p);
				}
			}
		}
		for (Map.Entry<String, String> file : job.getGeneratedFiles().entrySet()) {
			Path target = root.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
		}
		job.log(job.getPhases().stream().filter(p -> p.getStatus() == PhaseState.Status.EN_CURSO).map(PhaseState::getPhase)
			.findFirst().orElse(null), "INFO", files.size() + " archivos generados o actualizados: " + String.join(", ",
					files.keySet().stream().map(p -> p.substring(p.lastIndexOf('/') + 1)).toList()));
	}

	private static Map<String, String> onlySources(Map<String, String> files) {
		Map<String, String> result = new LinkedHashMap<>();
		files.forEach((path, content) -> {
			boolean java = (path.startsWith("src/main/java/") || path.startsWith("src/test/java/")) && path.endsWith(".java");
			boolean feature = path.startsWith(DeveloperAgent.FEATURES_DIR) && path.endsWith(".feature");
			if (java || feature) {
				result.put(path, content);
			}
		});
		return result;
	}

	/** SAST del código compilado; la evidencia queda en la migración. */
	private SecurityReport securityScan(MigrationJob job, Path dir) throws IOException, InterruptedException {
		ExecutionService.ToolRun run = execution.runSecurityScan(dir);
		SecurityReport report = run.ok()
				? securityAnalyzer.fromSpotBugs(run.report(), job.getGeneratedFiles(), job.getArchitecture().basePackage(),
						tail(run.output(), 3000))
				: new SecurityReport(SecurityAnalyzer.TOOL, false, 0, 0, 0, List.of(), List.of(), tail(run.output(), 3000));
		job.secured(report);
		return report;
	}

	private static final Pattern RULE_TAG = Pattern.compile("@(R-\\d{2,3})\\b");

	/** Resultado de Cucumber más la cobertura de reglas aprobadas por etiquetas @R-xx en los .feature. */
	private static BddReport bddReport(MigrationJob job, TestRunReport run) {
		Set<String> tagged = new LinkedHashSet<>();
		job.getGeneratedFiles().forEach((path, content) -> {
			if (path.startsWith(DeveloperAgent.FEATURES_DIR)) {
				Matcher m = RULE_TAG.matcher(content);
				while (m.find()) {
					tagged.add(m.group(1));
				}
			}
		});
		List<String> accepted = job.getAcceptedRules().stream().map(BusinessRule::getId).toList();
		List<String> covered = accepted.stream().filter(tagged::contains).toList();
		List<String> uncovered = accepted.stream().filter(id -> !tagged.contains(id)).toList();
		return new BddReport(run.compiled(), run.testsFound(), run.succeeded(), run.failed(), covered, uncovered,
				tail(run.output(), 4000));
	}

	private static String bddProblem(BddReport bdd) {
		if (bdd == null || !bdd.executed()) {
			return "Los escenarios BDD no se pudieron ejecutar:\n" + (bdd == null ? "" : bdd.output());
		}
		if (bdd.scenarios() == 0) {
			return "Cucumber no encontró escenarios: revisa la carpeta " + DeveloperAgent.FEATURES_DIR
					+ ", la cabecera '# language: es' y el paquete de los pasos.\n" + bdd.output();
		}
		if (bdd.failed() > 0 || bdd.passed() < bdd.scenarios()) {
			return (bdd.scenarios() - bdd.passed()) + " escenarios BDD fallan o tienen pasos sin definir:\n" + bdd.output();
		}
		if (!bdd.uncoveredRules().isEmpty()) {
			return "Faltan escenarios etiquetados para las reglas: " + String.join(", ", bdd.uncoveredRules());
		}
		return null;
	}

	private static <T> T await(java.util.concurrent.Future<T> future) {
		try {
			return future.get();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
		catch (java.util.concurrent.ExecutionException ex) {
			throw ex.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex.getCause());
		}
	}

	private static Map<String, String> mainFiles(MigrationJob job) {
		Map<String, String> result = new LinkedHashMap<>();
		job.getGeneratedFiles().forEach((path, content) -> {
			if (path.startsWith("src/main/java/")) {
				result.put(path, content);
			}
		});
		return result;
	}

	private LegacyPlatform platform(MigrationJob job) {
		return platforms.stream()
			.filter(p -> p.language().equals(job.getLanguage()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Sin plataforma para " + job.getLanguage()));
	}

	private static String mainClass(MigrationJob job) {
		return DeveloperAgent.mainClass(job.getArchitecture().basePackage());
	}

	private Path jobDir(MigrationJob job) throws IOException {
		Path dir = properties.workspace().resolve("jobs").resolve(job.getId()).toAbsolutePath();
		Files.createDirectories(dir);
		return dir;
	}

	private static PhaseState begin(MigrationJob job, Phase phase, String detail) {
		PhaseState state = job.phase(phase);
		state.start(detail);
		job.log(phase, "INFO", "Fase " + phase.getNumber() + " · " + phase.getTitle() + ": " + detail);
		return state;
	}

	private static void pass(MigrationJob job, PhaseState phase, boolean gatePassed, String result) {
		if (!gatePassed) {
			throw new GateFailedException(phase.getPhase(), result);
		}
		phase.complete(true, result);
		job.log(phase.getPhase(), "OK", "Quality gate superado: " + result);
	}

	private static void abort(MigrationJob job, Exception ex) {
		Phase failed = ex instanceof GateFailedException gate ? gate.phase()
				: job.getPhases()
					.stream()
					.filter(p -> p.getStatus() == PhaseState.Status.EN_CURSO)
					.map(PhaseState::getPhase)
					.findFirst()
					.orElse(null);
		boolean expected = ex instanceof GateFailedException || ex instanceof AgentException;
		String message = expected ? ex.getMessage()
				: "Error inesperado (" + ex.getClass().getSimpleName() + "): " + ex.getMessage();
		if (!expected) {
			log.error("Job {} falló", job.getId(), ex);
		}
		if (failed != null) {
			job.phase(failed).fail(message);
		}
		job.log(failed, "ERROR", message);
		job.fail(message);
		if (ex instanceof InterruptedException) {
			Thread.currentThread().interrupt();
		}
	}

	private static String tail(String text, int max) {
		if (text == null) {
			return "";
		}
		return text.length() <= max ? text : "…" + text.substring(text.length() - max);
	}

	private static String sanitizeFileName(String name) {
		String base = name == null ? "" : name.replaceAll(".*[/\\\\]", "").replaceAll("[^A-Za-z0-9._-]", "_");
		if (base.isBlank() || base.startsWith(".")) {
			base = "PROGRAMA.cbl";
		}
		return base.contains(".") ? base : base + ".cbl";
	}

	private static String blankToDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.strip();
	}

	private static <T> List<T> safe(List<T> list) {
		return list == null ? List.of() : list;
	}

}
