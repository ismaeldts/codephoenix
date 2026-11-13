package com.challenge.codephoenix.domain;

public enum Phase {

	INGESTA(1, "Ingesta y preparación", "0 secretos o datos sensibles; lenguaje identificado"),
	ANALISIS(2, "Análisis estático y descubrimiento", "100% de rutinas y dependencias inventariadas"),
	REGLAS(3, "Extracción de reglas de negocio", "Cada regla con líneas de origen y nivel de confianza"),
	VALIDACION(4, "Validación humana (HITL)", "Aprobación explícita del ingeniero"),
	ARQUITECTURA(5, "Diseño de arquitectura destino", "Alineación con arquitectura hexagonal"),
	GENERACION(6, "Transformación y generación de código", "Compilación exitosa; 0 hallazgos críticos"),
	VERIFICACION(7, "Verificación funcional y calidad", "100% de casos de equivalencia aprobados"),
	EMPAQUETADO(8, "Empaquetado y entrega", "Ejecución reproducible con un solo comando");

	private final int number;
	private final String title;
	private final String qualityGate;

	Phase(int number, String title, String qualityGate) {
		this.number = number;
		this.title = title;
		this.qualityGate = qualityGate;
	}

	public int getNumber() {
		return number;
	}

	public String getTitle() {
		return title;
	}

	public String getQualityGate() {
		return qualityGate;
	}

}
