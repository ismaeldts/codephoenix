package com.challenge.codephoenix.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class MigrationJobSnapshotTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private MigrationJob sampleJob() {
		MigrationJob job = new MigrationJob("abc12345", "AMORTIZA.cbl", "Créditos", "Java 21", "CODIGO ORIGINAL",
				List.of("000100000000180000012700"));
		job.ingested("COBOL", "CODIGO ENMASCARADO", List.of(new MaskingFinding("CONTRASENA", 14, "[CONTRASENA_1]")));
		job.phase(Phase.INGESTA).start("ingesta");
		job.phase(Phase.INGESTA).complete(true, "ok");
		job.rulesExtracted("resumen", List.of(new BusinessRule("R-01", "Monto", "VALIDACION", "desc", "cond", "acc",
				"Escenario", 90, 94, "1000-VALIDAR", 0.97, List.of("supuesto"))), List.of("ACCEPT"));
		job.rule("R-01").orElseThrow().adjust("nueva desc", null, null, "ajustada por negocio");
		job.codeGenerated(Map.of("src/main/java/A.java", "class A {}"));
		job.verified(new EquivalenceReport(1, 1, 0, 1, List.of(new CaseResult("TC01", "d", "NOMINAL", "x", true,
				List.of("HDR|1"), List.of("HDR|1"), List.of()))));
		job.log(Phase.INGESTA, "OK", "evento");
		return job;
	}

	@Test
	void snapshotSurvivesJsonRoundTrip() {
		MigrationJob original = sampleJob();

		String json = mapper.writeValueAsString(original.snapshot());
		MigrationJob restored = MigrationJob.restore(mapper.readValue(json, JobSnapshot.class), "CODIGO ORIGINAL");

		assertEquals(mapper.writeValueAsString(original.snapshot()), mapper.writeValueAsString(restored.snapshot()));
		assertEquals(BusinessRule.Status.AJUSTADA, restored.getRules().getFirst().getStatus());
		assertEquals("nueva desc", restored.getRules().getFirst().getDescription());
		assertEquals(PhaseState.Status.COMPLETADA, restored.phase(Phase.INGESTA).getStatus());
		assertEquals(MigrationJob.Status.ESPERANDO_APROBACION, restored.getStatus());
		assertEquals(List.of("000100000000180000012700"), restored.getCaseInputs());
	}

	@Test
	void snapshotNeverContainsTheOriginalCode() {
		String json = mapper.writeValueAsString(sampleJob().snapshot());

		assertTrue(json.contains("CODIGO ENMASCARADO"));
		assertTrue(!json.contains("CODIGO ORIGINAL"));
	}

	@Test
	void interruptedJobFailsItsRunningPhase() {
		MigrationJob job = new MigrationJob("x", "A.cbl", "d", "Java 21", "", List.of());
		job.phase(Phase.ANALISIS).start("analizando");

		job.interrupted("servidor detenido");

		assertEquals(MigrationJob.Status.FALLIDO, job.getStatus());
		assertEquals(PhaseState.Status.FALLIDA, job.phase(Phase.ANALISIS).getStatus());
		assertEquals("servidor detenido", job.getError());
	}

}
