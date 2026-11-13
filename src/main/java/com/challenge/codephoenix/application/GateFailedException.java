package com.challenge.codephoenix.application;

import com.challenge.codephoenix.domain.Phase;

/** Una fase no superó su quality gate: el flujo se detiene en esa fase. */
public class GateFailedException extends RuntimeException {

	private final Phase phase;

	public GateFailedException(Phase phase, String message) {
		super(message);
		this.phase = phase;
	}

	public Phase phase() {
		return phase;
	}

}
