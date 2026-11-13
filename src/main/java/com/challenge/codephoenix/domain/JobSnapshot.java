package com.challenge.codephoenix.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Copia serializable del estado de una migración, para persistirla en disco y
 * recuperarla tras un reinicio. No incluye el código original (puede contener
 * secretos): se relee desde la copia local del artefacto.
 */
public record JobSnapshot(
		int schemaVersion,
		String id,
		Instant createdAt,
		Instant finishedAt,
		String sourceName,
		String domainName,
		String targetStack,
		List<String> caseInputs,
		MigrationJob.Status status,
		String language,
		String maskedCode,
		List<MaskingFinding> maskingFindings,
		StaticAnalysis analysis,
		String explanation,
		String legacySchema,
		List<String> inferredTables,
		String inferredDdl,
		String rulesSummary,
		List<String> infrastructureLogic,
		List<RuleSnapshot> rules,
		int specVersion,
		ArchitectureDesign architecture,
		List<TestCase> testCases,
		Map<String, String> generatedFiles,
		int generationAttempts,
		EquivalenceReport equivalence,
		TestRunReport testRun,
		QualityReport quality,
		SecurityReport security,
		BddReport bdd,
		List<DecisionTable> decisionTables,
		String executiveSummary,
		String packageFile,
		String error,
		List<PhaseSnapshot> phases,
		List<LogEvent> events,
		List<AuditEntry> audit) {

	public static final int CURRENT_SCHEMA = 1;

	public record RuleSnapshot(String id, String name, String category, String description, String condition,
			String action, String gherkin, int sourceStartLine, int sourceEndLine, String sourceParagraph,
			double confidence, List<String> assumptions, BusinessRule.Status status, String reviewerComment) {
	}

	public record PhaseSnapshot(Phase phase, PhaseState.Status status, Instant startedAt, Instant finishedAt,
			String detail, Boolean gatePassed, String gateResult) {
	}

}
