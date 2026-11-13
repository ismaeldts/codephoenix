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
		assertEquals("El parámetro P_ESTADO no existe o no es de entrada en LIQUIDA_SINIESTRO",
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

	private static final String PACKAGE = """
			CREATE TABLE cliente (id NUMBER(10) PRIMARY KEY, nombre VARCHAR2(50));
			CREATE TABLE pedido (id NUMBER(10) PRIMARY KEY, cliente_id NUMBER(10) REFERENCES cliente (id),
			  estado VARCHAR2(10), usuario VARCHAR2(30), fecha DATE);
			CREATE SEQUENCE seq_pedido START WITH 100 INCREMENT BY 1;
			CREATE OR REPLACE PACKAGE pkg_pedidos AS
			    PROCEDURE pr_aprobar (p_id IN NUMBER);
			    PROCEDURE pr_lote (p_fecha IN DATE DEFAULT TRUNC(SYSDATE));
			END pkg_pedidos;
			/
			CREATE OR REPLACE PACKAGE BODY pkg_pedidos AS
			    PROCEDURE pr_aprobar (p_id IN NUMBER) IS
			    BEGIN
			        UPDATE pedido SET estado = 'OK', usuario = USER, fecha = SYSDATE WHERE id = p_id;
			    END pr_aprobar;
			    PROCEDURE pr_lote (p_fecha IN DATE DEFAULT TRUNC(SYSDATE)) IS
			    BEGIN
			        FOR r IN (SELECT id FROM pedido) LOOP
			            pr_aprobar(r.id);
			        END LOOP;
			        DBMS_OUTPUT.PUT_LINE('fin');
			    END pr_lote;
			END pkg_pedidos;
			/
			""";

	@Test
	void callsTheChosenRoutineRestartsSequencesAndInsertsParentsFirst() {
		PlsqlModel model = new PlsqlStaticAnalyzer().analyze(PACKAGE).plsql();
		PlsqlHarness.ParsedCase parsed = PlsqlHarness.parse("""
				CALL|PR_LOTE
				ROW|PEDIDO|ID=1|CLIENTE_ID=7|ESTADO=NUEVO
				ROW|CLIENTE|ID=7|NOMBRE=ANA
				""");

		assertNull(PlsqlHarness.validate(model, parsed));
		String script = PlsqlHarness.caseScript(model, parsed);

		assertTrue(script.contains("ALTER SEQUENCE SEQ_PEDIDO RESTART START WITH 100"));
		assertTrue(script.indexOf("INSERT INTO CLIENTE") < script.indexOf("INSERT INTO PEDIDO"));
		assertTrue(script.indexOf("DELETE FROM PEDIDO") < script.indexOf("DELETE FROM CLIENTE"));
		assertTrue(script.contains("PKG_PEDIDOS.PR_LOTE;"), "el parámetro omitido toma su DEFAULT");
		assertTrue(script.indexOf("COMMIT;") < script.indexOf("PKG_PEDIDOS.PR_LOTE;"),
				"la preparación se confirma antes de invocar para no bloquear transacciones autónomas");
		assertTrue(script.contains("RETURN 'USUARIO_BD'"));
		assertTrue(script.contains("RETURN 'FECHA_SISTEMA'"));
		assertTrue(PlsqlHarness.validate(model, PlsqlHarness.parse("CALL|PR_OCULTA")).contains("no es pública"));
	}

	@Test
	void setupScriptTerminatesTheProgramUnit() {
		String setup = PlsqlHarness.setupScript("CREATE TABLE T (A NUMBER);", "CREATE OR REPLACE PROCEDURE P AS BEGIN NULL; END;");

		assertTrue(setup.contains("CREATE OR REPLACE PROCEDURE P AS BEGIN NULL; END;\n/"));
		assertTrue(setup.contains("SET SQLBLANKLINES ON"));
	}

}
