package com.challenge.codephoenix.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("codephoenix")
public record CodephoenixProperties(Path workspace, Sandbox sandbox, Oracle oracle, int maxCorrectionAttempts,
		ManualEstimate manualEstimate) {

	public record Sandbox(String image, Duration timeout) {
	}

	/** Sandbox de Oracle para caracterizar programas PL/SQL. */
	public record Oracle(String image, String container, String network, boolean warmUp, Duration startupTimeout,
			Duration timeout) {
	}

	/** Supuestos para estimar el esfuerzo del análisis manual equivalente. */
	public record ManualEstimate(int locPerHour, int hourlyRateUsd) {
	}

}
