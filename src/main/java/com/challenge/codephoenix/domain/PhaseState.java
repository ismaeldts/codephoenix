package com.challenge.codephoenix.domain;

import java.time.Duration;
import java.time.Instant;

public class PhaseState {

	public enum Status { PENDIENTE, EN_CURSO, ESPERANDO_APROBACION, COMPLETADA, FALLIDA }

	private final Phase phase;
	private Status status = Status.PENDIENTE;
	private Instant startedAt;
	private Instant finishedAt;
	private String detail;
	private Boolean gatePassed;
	private String gateResult;

	public PhaseState(Phase phase) {
		this.phase = phase;
	}

	static PhaseState restore(JobSnapshot.PhaseSnapshot s) {
		PhaseState state = new PhaseState(s.phase());
		state.status = s.status();
		state.startedAt = s.startedAt();
		state.finishedAt = s.finishedAt();
		state.detail = s.detail();
		state.gatePassed = s.gatePassed();
		state.gateResult = s.gateResult();
		return state;
	}

	JobSnapshot.PhaseSnapshot snapshot() {
		return new JobSnapshot.PhaseSnapshot(phase, status, startedAt, finishedAt, detail, gatePassed, gateResult);
	}

	public void start(String detail) {
		this.status = Status.EN_CURSO;
		this.startedAt = Instant.now();
		this.detail = detail;
	}

	public void progress(String detail) {
		this.detail = detail;
	}

	public void awaitApproval(String detail) {
		this.status = Status.ESPERANDO_APROBACION;
		this.detail = detail;
	}

	public void complete(boolean gatePassed, String gateResult) {
		this.status = gatePassed ? Status.COMPLETADA : Status.FALLIDA;
		this.finishedAt = Instant.now();
		this.gatePassed = gatePassed;
		this.gateResult = gateResult;
	}

	public void fail(String reason) {
		this.status = Status.FALLIDA;
		this.finishedAt = Instant.now();
		this.gatePassed = false;
		this.gateResult = reason;
	}

	public Phase getPhase() {
		return phase;
	}

	public int getNumber() {
		return phase.getNumber();
	}

	public String getTitle() {
		return phase.getTitle();
	}

	public String getQualityGate() {
		return phase.getQualityGate();
	}

	public Status getStatus() {
		return status;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public Long getDurationMs() {
		if (startedAt == null) {
			return null;
		}
		Instant end = finishedAt != null ? finishedAt : Instant.now();
		return Duration.between(startedAt, end).toMillis();
	}

	public String getDetail() {
		return detail;
	}

	public Boolean getGatePassed() {
		return gatePassed;
	}

	public String getGateResult() {
		return gateResult;
	}

}
