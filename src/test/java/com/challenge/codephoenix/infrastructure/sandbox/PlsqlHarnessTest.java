package com.challenge.codephoenix.infrastructure.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.challenge.codephoenix.domain.PlsqlModel;
import com.challenge.codephoenix.infrastructure.analysis.PlsqlStaticAnalyzer;

class PlsqlHarnessTest {

	private static final String CASE = """
			PARAM|P_SINIESTRO_ID|1001
			ROW|POLIZAS|POLIZA_ID=1|TIPO_PRODUCTO=AUTO|ESTADO=VIGENTE|FECHA_INICIO=2024-01-01|SUMA_ASEGURADA=50000|DEDUCIBLE=500|PORC_COASEGURO=10
			ROW|SINIESTROS|SINIESTRO_ID=1001|POLIZA_ID=1|FECHA_OCURRENCIA=2024-06-01|MONTO_RECLAMADO=12345.67|CAUSA=CHOQUE|ESTADO=ABIERTO|MONTO_LIQUIDADO=\\N
			""";

	private PlsqlModel model() throws IOException {
		String code = new ClassPathResource("samples/LIQUIDA_SINIESTRO.sql").getContentAsString(StandardCharsets.UTF_8);
		return new PlsqlStaticAnalyzer().analyze(code).plsql();
	}

	@Test
	void parsesParamsAndRows() {
		PlsqlHarness.ParsedCase parsed = PlsqlHarness.parse(CASE);

		assertEquals("1001", parsed.params().get("P_SINIESTRO_ID"));
		assertEquals(2, parsed.rows().size());
		assertEquals("\\N", parsed.rows().get(1).values().get("MONTO_LIQUIDADO"));
	}

	@Test
	void rejectsUnknownLines() {
		assertThrows(IllegalArgumentException.class, () -> PlsqlHarness.parse("FOO|bar"));
	}

	@Test
	void validatesAgainstSignatureAndSchema() throws IOException {
		PlsqlModel model = model();

		assertNull(PlsqlHarness.validate(model, PlsqlHarness.parse(CASE)));
		assertEquals("El parámetro P_ESTADO no existe o no es de entrada",
				PlsqlHarness.validate(model, PlsqlHarness.parse("PARAM|P_ESTADO|X")));
		assertEquals("La columna POLIZAS.COLOR no existe",
				PlsqlHarness.validate(model, PlsqlHarness.parse("ROW|POLIZAS|COLOR=1")));
		assertTrue(PlsqlHarness.validate(model, PlsqlHarness.parse("ROW|POLIZAS|DEDUCIBLE=abc")).contains("número no válido"));
	}

	@Test
	void generatesInvocationOutputsAndSideEffectDumps() throws IOException {
		String script = PlsqlHarness.caseScript(model(), PlsqlHarness.parse(CASE));

		assertTrue(script.contains("INSERT INTO POLIZAS (POLIZA_ID, TIPO_PRODUCTO, ESTADO, FECHA_INICIO, SUMA_ASEGURADA, DEDUCIBLE, PORC_COASEGURO) "
				+ "VALUES (1, 'AUTO', 'VIGENTE', TO_DATE('2024-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS'), 50000, 500, 10);"));
		assertTrue(script.contains("MONTO_LIQUIDADO) VALUES (1001, 1, TO_DATE('2024-06-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS'), 12345.67, 'CHOQUE', 'ABIERTO', NULL);"));
		assertTrue(script.contains("LIQUIDA_SINIESTRO(P_SINIESTRO_ID => 1001, P_MONTO_PAGAR => cp_p_monto_pagar, P_ESTADO => cp_p_estado);"));
		assertTrue(script.contains("DBMS_OUTPUT.PUT_LINE('OUT|P_MONTO_PAGAR|' || cp_num(cp_p_monto_pagar));"));
		assertTrue(script.contains("DBMS_OUTPUT.PUT_LINE('ERR|' || SQLCODE || '|' || cp_err);"));
		assertTrue(script.contains("FOR r IN (SELECT * FROM PAGOS_SINIESTRO) LOOP"));
		assertTrue(script.contains("'|NETO=' || cp_num(r.NETO)"));
		assertTrue(script.contains("BEGIN DELETE FROM SINIESTROS; EXCEPTION WHEN OTHERS THEN NULL; END;"));
	}

	@Test
	void setupScriptTerminatesTheProgramUnit() {
		String setup = PlsqlHarness.setupScript("CREATE TABLE T (A NUMBER);", "CREATE OR REPLACE PROCEDURE P AS BEGIN NULL; END;");

		assertTrue(setup.contains("CREATE OR REPLACE PROCEDURE P AS BEGIN NULL; END;\n/"));
		assertTrue(setup.contains("SET SQLBLANKLINES ON"));
	}

}
