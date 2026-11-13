package com.challenge.codephoenix.domain;

import java.util.List;

/** Resultado determinista del análisis estático (fase 2), sin intervención del LLM. */
public record StaticAnalysis(
		String programId,
		int totalLines,
		int codeLines,
		int commentLines,
		List<Paragraph> paragraphs,
		List<DataItem> dataItems,
		List<CallEdge> callGraph,
		List<String> deadParagraphs,
		int goToCount,
		List<InfraOperation> infraOperations,
		int totalComplexity,
		List<RiskItem> riskMap,
		List<RecordField> inputRecord,
		PlsqlModel plsql) {

	/** Campo del registro de entrada que lee el programa (ACCEPT), con su posición en el registro. */
	public record RecordField(String name, int start, int length, int decimals) {
	}

	public record Paragraph(String name, int startLine, int endLine, int complexity, boolean business,
			boolean infrastructure, boolean reachable) {
	}

	public record DataItem(int level, String name, String picture, String usage, String value, int line,
			String javaType) {
	}

	public record CallEdge(String from, String to, String kind) {
	}

	public record InfraOperation(String verb, int line, String paragraph) {
	}

	public record RiskItem(String paragraph, String level, String reason) {
	}

}
