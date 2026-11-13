package com.challenge.codephoenix.domain;

import java.time.Instant;

/** Registro de cada llamada a un agente de IA (gobierno y observabilidad). */
public record AuditEntry(Instant at, String agent, String model, String promptSha256, boolean promptMasked,
		long inputTokens, long outputTokens, long durationMs, String outcome) {
}
