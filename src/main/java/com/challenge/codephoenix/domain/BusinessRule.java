package com.challenge.codephoenix.domain;

import java.util.List;

/**
 * Regla de negocio descubierta en el código legacy. Es mutable porque el
 * ingeniero puede ajustarla durante la validación humana (fase 4).
 */
public class BusinessRule {

	public enum Status { PROPUESTA, APROBADA, AJUSTADA, RECHAZADA }

	private String id;
	private String name;
	private String category;
	private String description;
	private String condition;
	private String action;
	private String gherkin;
	private int sourceStartLine;
	private int sourceEndLine;
	private String sourceParagraph;
	private double confidence;
	private List<String> assumptions;
	private Status status = Status.PROPUESTA;
	private String reviewerComment;

	public BusinessRule() {
	}

	public BusinessRule(String id, String name, String category, String description, String condition,
			String action, String gherkin, int sourceStartLine, int sourceEndLine, String sourceParagraph,
			double confidence, List<String> assumptions) {
		this.id = id;
		this.name = name;
		this.category = category;
		this.description = description;
		this.condition = condition;
		this.action = action;
		this.gherkin = gherkin;
		this.sourceStartLine = sourceStartLine;
		this.sourceEndLine = sourceEndLine;
		this.sourceParagraph = sourceParagraph;
		this.confidence = confidence;
		this.assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
	}

	static BusinessRule restore(JobSnapshot.RuleSnapshot s) {
		BusinessRule rule = new BusinessRule(s.id(), s.name(), s.category(), s.description(), s.condition(),
				s.action(), s.gherkin(), s.sourceStartLine(), s.sourceEndLine(), s.sourceParagraph(), s.confidence(),
				s.assumptions());
		rule.status = s.status();
		rule.reviewerComment = s.reviewerComment();
		return rule;
	}

	JobSnapshot.RuleSnapshot snapshot() {
		return new JobSnapshot.RuleSnapshot(id, name, category, description, condition, action, gherkin,
				sourceStartLine, sourceEndLine, sourceParagraph, confidence, assumptions, status, reviewerComment);
	}

	public void approve(String comment) {
		this.status = Status.APROBADA;
		this.reviewerComment = comment;
	}

	public void adjust(String description, String condition, String action, String comment) {
		if (description != null && !description.isBlank()) {
			this.description = description;
		}
		if (condition != null && !condition.isBlank()) {
			this.condition = condition;
		}
		if (action != null && !action.isBlank()) {
			this.action = action;
		}
		this.status = Status.AJUSTADA;
		this.reviewerComment = comment;
	}

	public void reject(String comment) {
		this.status = Status.RECHAZADA;
		this.reviewerComment = comment;
	}

	public boolean isDecided() {
		return status != Status.PROPUESTA;
	}

	public boolean isAccepted() {
		return status == Status.APROBADA || status == Status.AJUSTADA;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCategory() {
		return category;
	}

	public String getDescription() {
		return description;
	}

	public String getCondition() {
		return condition;
	}

	public String getAction() {
		return action;
	}

	public String getGherkin() {
		return gherkin;
	}

	public int getSourceStartLine() {
		return sourceStartLine;
	}

	public int getSourceEndLine() {
		return sourceEndLine;
	}

	public String getSourceParagraph() {
		return sourceParagraph;
	}

	public double getConfidence() {
		return confidence;
	}

	public List<String> getAssumptions() {
		return assumptions;
	}

	public Status getStatus() {
		return status;
	}

	public String getReviewerComment() {
		return reviewerComment;
	}

}
