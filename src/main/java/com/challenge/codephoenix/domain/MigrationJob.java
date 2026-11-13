package com.challenge.codephoenix.domain;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Estado completo de una migración. Lo escribe el orquestador en segundo plano
 * y lo lee la interfaz mediante consultas periódicas, por eso todos los
 * accesos están sincronizados.
 */
public class MigrationJob {

	public enum Status { EN_CURSO, ESPERANDO_APROBACION, COMPLETADO, FALLIDO }

	private final String id;
	private Instant createdAt = Instant.now();
	private final String sourceName;
	private final String domainName;
	private final String targetStack;
	private final String originalCode;
	private final List<String> caseInputs;

	private Status status = Status.EN_CURSO;
	private Instant finishedAt;
	private String language;
	private String maskedCode;
	private List<MaskingFinding> maskingFindings = List.of();
	private StaticAnalysis analysis;
	private String explanation;
	private String legacySchema;
	private List<String> inferredTables = List.of();
	private String inferredDdl;
	private String rulesSummary;
	private List<String> infrastructureLogic = List.of();
	private final List<BusinessRule> rules = new ArrayList<>();
	private int specVersion;
	private ArchitectureDesign architecture;
	private List<TestCase> testCases = List.of();
	private final Map<String, String> generatedFiles = new LinkedHashMap<>();
	private int generationAttempts;
	private EquivalenceReport equivalence;
	private TestRunReport testRun;
	private QualityReport quality;
	private SecurityReport security;
	private BddReport bdd;
	private List<DecisionTable> decisionTables = List.of();
	private String executiveSummary;
	private Path packageFile;
	private String error;
	private final Map<Phase, PhaseState> phases = new EnumMap<>(Phase.class);
	private final List<LogEvent> events = new ArrayList<>();
	private final List<AuditEntry> audit = new ArrayList<>();

	public MigrationJob(String id, String sourceName, String domainName, String targetStack, String originalCode,
			List<String> caseInputs) {
		this.id = id;
		this.sourceName = sourceName;
		this.domainName = domainName;
		this.targetStack = targetStack;
		this.originalCode = originalCode;
		this.caseInputs = caseInputs == null ? List.of() : List.copyOf(caseInputs);
		for (Phase phase : Phase.values()) {
			phases.put(phase, new PhaseState(phase));
		}
	}

	// ---- persistencia ----

	public synchronized JobSnapshot snapshot() {
		return new JobSnapshot(JobSnapshot.CURRENT_SCHEMA, id, createdAt, finishedAt, sourceName, domainName,
				targetStack, caseInputs, status, language, maskedCode, maskingFindings, analysis, explanation, legacySchema,
				inferredTables, inferredDdl,
				rulesSummary, infrastructureLogic, rules.stream().map(BusinessRule::snapshot).toList(), specVersion,
				architecture, testCases, new LinkedHashMap<>(generatedFiles), generationAttempts, equivalence, testRun,
				quality, security, bdd, decisionTables, executiveSummary,
				packageFile == null ? null : packageFile.toString(), error,
				phases.values().stream().map(PhaseState::snapshot).toList(), List.copyOf(events), List.copyOf(audit));
	}

	/** Reconstruye una migración guardada. {@code originalCode} se relee de la copia local del artefacto. */
	public static MigrationJob restore(JobSnapshot s, String originalCode) {
		MigrationJob job = new MigrationJob(s.id(), s.sourceName(), s.domainName(), s.targetStack(), originalCode,
				s.caseInputs());
		job.createdAt = s.createdAt();
		job.finishedAt = s.finishedAt();
		job.status = s.status();
		job.language = s.language();
		job.maskedCode = s.maskedCode();
		job.maskingFindings = orEmpty(s.maskingFindings());
		job.analysis = s.analysis();
		job.explanation = s.explanation();
		job.legacySchema = s.legacySchema();
		job.inferredTables = orEmpty(s.inferredTables());
		job.inferredDdl = s.inferredDdl();
		job.rulesSummary = s.rulesSummary();
		job.infrastructureLogic = orEmpty(s.infrastructureLogic());
		orEmpty(s.rules()).forEach(r -> job.rules.add(BusinessRule.restore(r)));
		job.specVersion = s.specVersion();
		job.architecture = s.architecture();
		job.testCases = orEmpty(s.testCases());
		if (s.generatedFiles() != null) {
			job.generatedFiles.putAll(s.generatedFiles());
		}
		job.generationAttempts = s.generationAttempts();
		job.equivalence = s.equivalence();
		job.testRun = s.testRun();
		job.quality = s.quality();
		job.security = s.security();
		job.bdd = s.bdd();
		job.decisionTables = orEmpty(s.decisionTables());
		job.executiveSummary = s.executiveSummary();
		job.packageFile = s.packageFile() == null ? null : Path.of(s.packageFile());
		job.error = s.error();
		orEmpty(s.phases()).forEach(p -> job.phases.put(p.phase(), PhaseState.restore(p)));
		job.events.addAll(orEmpty(s.events()));
		job.audit.addAll(orEmpty(s.audit()));
		return job;
	}

	/** Una migración que estaba en curso cuando se detuvo el servidor no puede continuar: se marca como fallida. */
	public synchronized void interrupted(String reason) {
		phases.values()
			.stream()
			.filter(p -> p.getStatus() == PhaseState.Status.EN_CURSO)
			.forEach(p -> p.fail(reason));
		events.add(new LogEvent(Instant.now(), null, "ERROR", reason));
		this.status = Status.FALLIDO;
		this.error = reason;
		this.finishedAt = Instant.now();
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}

	// ---- comandos usados por el orquestador ----

	public synchronized PhaseState phase(Phase phase) {
		return phases.get(phase);
	}

	public synchronized void log(Phase phase, String level, String message) {
		events.add(new LogEvent(Instant.now(), phase == null ? null : phase.name(), level, message));
	}

	public synchronized void recordAudit(AuditEntry entry) {
		audit.add(entry);
	}

	public synchronized void ingested(String language, String maskedCode, List<MaskingFinding> findings) {
		this.language = language;
		this.maskedCode = maskedCode;
		this.maskingFindings = List.copyOf(findings);
	}

	public synchronized void analyzed(StaticAnalysis analysis) {
		this.analysis = analysis;
	}

	public synchronized void explained(String explanation) {
		this.explanation = explanation;
	}

	/** Esquema de datos con el que se ejecuta el legacy; {@code inferred} son las tablas que dedujo la IA. */
	public synchronized void schemaResolved(String ddl, List<String> inferred, String inferredDdl) {
		this.legacySchema = ddl;
		this.inferredTables = List.copyOf(inferred);
		this.inferredDdl = inferredDdl;
	}

	public synchronized void rulesExtracted(String summary, List<BusinessRule> extracted, List<String> infra) {
		this.rulesSummary = summary;
		this.rules.clear();
		this.rules.addAll(extracted);
		this.infrastructureLogic = List.copyOf(infra);
		this.status = Status.ESPERANDO_APROBACION;
	}

	public synchronized void decisionTablesExtracted(List<DecisionTable> tables) {
		this.decisionTables = List.copyOf(tables);
	}

	public synchronized void secured(SecurityReport security) {
		this.security = security;
	}

	public synchronized void bddVerified(BddReport bdd) {
		this.bdd = bdd;
	}

	public synchronized Optional<BusinessRule> rule(String ruleId) {
		return rules.stream().filter(r -> r.getId().equals(ruleId)).findFirst();
	}

	public synchronized void specificationApproved() {
		this.specVersion++;
		this.status = Status.EN_CURSO;
	}

	public synchronized void designed(ArchitectureDesign design) {
		this.architecture = design;
	}

	public synchronized void testCasesDesigned(List<TestCase> cases) {
		this.testCases = List.copyOf(cases);
	}

	public synchronized void codeGenerated(Map<String, String> files) {
		this.generatedFiles.putAll(files);
		this.generationAttempts++;
	}

	public synchronized void verified(EquivalenceReport equivalence) {
		this.equivalence = equivalence;
	}

	public synchronized void tested(TestRunReport testRun, QualityReport quality) {
		this.testRun = testRun;
		this.quality = quality;
	}

	public synchronized void packaged(Path packageFile, String executiveSummary) {
		this.packageFile = packageFile;
		this.executiveSummary = executiveSummary;
	}

	public synchronized void finish() {
		this.status = Status.COMPLETADO;
		this.finishedAt = Instant.now();
	}

	public synchronized void fail(String error) {
		this.status = Status.FALLIDO;
		this.error = error;
		this.finishedAt = Instant.now();
	}

	// ---- consultas ----

	public String getId() {
		return id;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public synchronized Instant getFinishedAt() {
		return finishedAt;
	}

	public synchronized long getElapsedMs() {
		return Duration.between(createdAt, finishedAt != null ? finishedAt : Instant.now()).toMillis();
	}

	public String getSourceName() {
		return sourceName;
	}

	public String getDomainName() {
		return domainName;
	}

	public String getTargetStack() {
		return targetStack;
	}

	@JsonIgnore
	public String getOriginalCode() {
		return originalCode;
	}

	@JsonIgnore
	public List<String> getCaseInputs() {
		return caseInputs;
	}

	public synchronized Status getStatus() {
		return status;
	}

	public synchronized String getLanguage() {
		return language;
	}

	/** La interfaz solo recibe la versión enmascarada del código. */
	public synchronized String getMaskedCode() {
		return maskedCode;
	}

	public synchronized List<MaskingFinding> getMaskingFindings() {
		return maskingFindings;
	}

	public synchronized StaticAnalysis getAnalysis() {
		return analysis;
	}

	public synchronized String getExplanation() {
		return explanation;
	}

	public synchronized String getLegacySchema() {
		return legacySchema;
	}

	public synchronized List<String> getInferredTables() {
		return inferredTables;
	}

	@JsonIgnore
	public synchronized String getInferredDdl() {
		return inferredDdl;
	}

	public synchronized String getRulesSummary() {
		return rulesSummary;
	}

	public synchronized List<String> getInfrastructureLogic() {
		return infrastructureLogic;
	}

	public synchronized List<BusinessRule> getRules() {
		return List.copyOf(rules);
	}

	public synchronized List<BusinessRule> getAcceptedRules() {
		return rules.stream().filter(BusinessRule::isAccepted).toList();
	}

	public synchronized int getSpecVersion() {
		return specVersion;
	}

	public synchronized ArchitectureDesign getArchitecture() {
		return architecture;
	}

	public synchronized List<TestCase> getTestCases() {
		return testCases;
	}

	public synchronized Map<String, String> getGeneratedFiles() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(generatedFiles));
	}

	public synchronized int getGenerationAttempts() {
		return generationAttempts;
	}

	public synchronized EquivalenceReport getEquivalence() {
		return equivalence;
	}

	public synchronized TestRunReport getTestRun() {
		return testRun;
	}

	public synchronized QualityReport getQuality() {
		return quality;
	}

	public synchronized SecurityReport getSecurity() {
		return security;
	}

	public synchronized BddReport getBdd() {
		return bdd;
	}

	public synchronized List<DecisionTable> getDecisionTables() {
		return decisionTables;
	}

	public synchronized String getExecutiveSummary() {
		return executiveSummary;
	}

	@JsonIgnore
	public synchronized Path getPackageFile() {
		return packageFile;
	}

	public synchronized boolean isPackageReady() {
		return packageFile != null;
	}

	public synchronized String getError() {
		return error;
	}

	public synchronized List<PhaseState> getPhases() {
		return List.copyOf(phases.values());
	}

	public synchronized List<LogEvent> getEvents() {
		return List.copyOf(events);
	}

	public synchronized List<AuditEntry> getAudit() {
		return List.copyOf(audit);
	}

	public synchronized long getTotalInputTokens() {
		return audit.stream().mapToLong(AuditEntry::inputTokens).sum();
	}

	public synchronized long getTotalOutputTokens() {
		return audit.stream().mapToLong(AuditEntry::outputTokens).sum();
	}

}
