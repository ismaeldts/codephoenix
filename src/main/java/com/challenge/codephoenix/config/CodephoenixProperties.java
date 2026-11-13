package com.challenge.codephoenix.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("codephoenix")
public record CodephoenixProperties(Path workspace, Sandbox sandbox, Oracle oracle, int maxCorrectionAttempts,
		ManualEstimate manualEstimate) {

	/**
	 * Sandbox de ejecución. {@code volume}: si CodePhoenix corre dentro de un contenedor, el
	 * workspace es un volumen Docker con este nombre y los sandboxes lo montan por nombre (Docker
	 * resuelve los bind mounts en el host, no en el contenedor de la aplicación). Vacío = bind mount.
	 */
	public record Sandbox(String image, Duration timeout, String volume) {

		public boolean usesVolume() {
			return volume != null && !volume.isBlank();
		}

	}

	/** Sandbox de Oracle para caracterizar programas PL/SQL. */
	public record Oracle(String image, String container, String network, boolean warmUp, Duration startupTimeout,
			Duration timeout) {
	}

	/** Supuestos para estimar el esfuerzo del análisis manual equivalente. */
	public record ManualEstimate(int locPerHour, int hourlyRateUsd) {
	}

}
