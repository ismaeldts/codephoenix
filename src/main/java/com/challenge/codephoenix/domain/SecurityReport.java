package com.challenge.codephoenix.domain;

import java.util.List;

/**
 * Resultado del análisis de seguridad del código generado: SAST sobre el bytecode
 * (SpotBugs + FindSecBugs) y revisión de dependencias de producción.
 */
public record SecurityReport(String tool, boolean executed, int critical, int major, int minor,
		List<QualityReport.Finding> findings, List<String> productionDependencies, String output) {

	public static SecurityReport notExecuted(String reason) {
		return new SecurityReport("SpotBugs + FindSecBugs", false, 0, 0, 0, List.of(), List.of(), reason);
	}

}
