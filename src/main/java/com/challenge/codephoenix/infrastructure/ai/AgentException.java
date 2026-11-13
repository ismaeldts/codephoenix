package com.challenge.codephoenix.infrastructure.ai;

/** Fallo de un agente de IA con un mensaje comprensible para el usuario. */
public class AgentException extends RuntimeException {

	public AgentException(String message, Throwable cause) {
		super(message, cause);
	}

}
