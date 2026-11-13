package com.challenge.codephoenix.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.challenge.codephoenix.domain.MigrationJob;
import com.challenge.codephoenix.domain.StaticAnalysis;
import com.challenge.codephoenix.domain.TestCase;
import com.challenge.codephoenix.infrastructure.ai.QaAgent;
import com.challenge.codephoenix.infrastructure.sandbox.ExecutionService;

/**
 * Todo lo que depende del lenguaje legacy (Strategy): cómo se analiza, cómo se
 * ejecuta el original para obtener el Golden Master, cómo se codifican los
 * casos de prueba y qué contrato de E/S debe cumplir el código moderno. El
 * orquestador es el mismo para todos los lenguajes.
 */
public interface LegacyPlatform {

	/** Lenguaje que atiende, tal como lo identifica el detector (por ejemplo "COBOL" o "PL/SQL"). */
	String language();

	/** Motor real con el que se ejecuta el programa original, para los reportes. */
	String runtimeDescription();

	/** Cómo se llama una unidad de código en este lenguaje (párrafo, procedimiento...). */
	String unitTerm();

	/** Fase 1: verifica que el artefacto se puede ejecutar; devuelve el resultado para el quality gate. */
	String verifyArtifact(MigrationJob job, Path jobDir) throws IOException, InterruptedException;

	/** Fase 2: análisis estático determinista. */
	StaticAnalysis analyze(MigrationJob job);

	/** Dependencias (llamadas, rutinas externas) que no se encuentran en el artefacto. */
	List<String> unresolvedDependencies(StaticAnalysis analysis);

	/** Fase 2: deja el artefacto listo para ejecutarse (por ejemplo, esquema y compilación en la base de datos). */
	void prepareExecution(MigrationJob job, Path jobDir) throws IOException, InterruptedException;

	/** Instrucciones para que el Agente QA diseñe casos con el formato de entrada de este lenguaje. */
	String caseInstructions(MigrationJob job);

	/** Convierte los casos del Agente QA y los aportados por el usuario en casos ejecutables. */
	List<TestCase> toTestCases(MigrationJob job, List<QaAgent.CaseDraft> drafts);

	/** Ejecuta el programa original con cada caso y devuelve su salida (Golden Master). */
	ExecutionService.RunOutcome runLegacy(MigrationJob job, Path jobDir, List<TestCase> cases)
			throws IOException, InterruptedException;

	/** Paquetes y adaptadores que el Arquitecto debe prever en el diseño destino. */
	String architectureHint(MigrationJob job);

	/** Contrato de E/S del adaptador CLI de caracterización que debe implementar el código moderno. */
	String ioContract(MigrationJob job);

	/** Comando de ejemplo para ejecutar el paquete entregado. */
	String sampleRunCommand(MigrationJob job);

}
