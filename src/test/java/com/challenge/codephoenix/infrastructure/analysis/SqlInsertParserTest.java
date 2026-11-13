package com.challenge.codephoenix.infrastructure.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.challenge.codephoenix.domain.PlsqlModel;

class SqlInsertParserTest {

	private final List<PlsqlModel.Table> schema = List.of(
			new PlsqlModel.Table("PARAMETROS", List.of(new PlsqlModel.Column("COD", "VARCHAR2(40)", false),
					new PlsqlModel.Column("VALOR", "NUMBER(14,4)", true), new PlsqlModel.Column("TEXTO", "VARCHAR2(200)", true)),
					List.of()),
			new PlsqlModel.Table("SINIESTROS", List.of(new PlsqlModel.Column("ID", "NUMBER(10)", false),
					new PlsqlModel.Column("FECHA", "DATE", false), new PlsqlModel.Column("DESCRIPCION", "VARCHAR2(400)", true)),
					List.of()));

	@Test
	void parsesPositionalAndNamedInsertsWithComments() {
		String sql = """
				-- parámetros
				INSERT INTO parametros VALUES ('DIAS_MAX', 30, NULL);   -- comentario
				INSERT INTO parametros VALUES ('VERSION', NULL, 'it''s 3.2');
				/* bloque */
				INSERT INTO siniestros (id, descripcion, fecha)
				VALUES (1001, 'Choque, leve (sin heridos)', DATE '2026-02-10');
				INSERT INTO siniestros VALUES (1002, TO_DATE('15/01/2026', 'DD/MM/YYYY'), 'Incendio');
				COMMIT;
				""";

		SqlInsertParser.Result result = SqlInsertParser.parseInserts(sql, schema);

		assertEquals(4, result.rows().size());
		assertEquals(Map.of("COD", "DIAS_MAX", "VALOR", "30", "TEXTO", "\\N"), result.rows().get(0).values());
		assertEquals("it's 3.2", result.rows().get(1).values().get("TEXTO"));
		assertEquals("Choque, leve (sin heridos)", result.rows().get(2).values().get("DESCRIPCION"));
		assertEquals("2026-02-10", result.rows().get(2).values().get("FECHA"));
		assertEquals("2026-01-15", result.rows().get(3).values().get("FECHA"));
		assertTrue(result.skipped().isEmpty());
	}

	@Test
	void skipsRowsWithNonLiteralValuesOrUnknownTables() {
		String sql = """
				INSERT INTO siniestros VALUES (1, SYSDATE, 'x');
				INSERT INTO otra_tabla VALUES (1);
				""";

		SqlInsertParser.Result result = SqlInsertParser.parseInserts(sql, schema);

		assertTrue(result.rows().isEmpty());
		assertEquals(2, result.skipped().size());
	}

	@Test
	void parsesCallsInsideAnonymousBlocks() {
		String sql = """
				SET SERVEROUTPUT ON
				BEGIN
				    pkg_liq.pr_procesar_lote(p_fecha_proceso => DATE '2026-03-31');
				    DBMS_OUTPUT.PUT_LINE('fin');
				END;
				/
				BEGIN pkg_liq.pr_uno(1001, DATE '2026-01-01'); END;
				/
				""";

		List<SqlInsertParser.Call> calls = SqlInsertParser.parseCalls(sql);

		assertEquals(2, calls.size());
		assertEquals("PKG_LIQ.PR_PROCESAR_LOTE", calls.get(0).routine());
		assertEquals(Map.of("P_FECHA_PROCESO", "2026-03-31"), calls.get(0).arguments());
		assertEquals(Map.of("#1", "1001", "#2", "2026-01-01"), calls.get(1).arguments());
	}

	@Test
	void convertsLiterals() {
		assertEquals(Optional.of("12345.67"), SqlInsertParser.literal(" 12345.67 "));
		assertEquals(Optional.of("\\N"), SqlInsertParser.literal("null"));
		assertEquals(Optional.of("2026-03-31 10:15:00"), SqlInsertParser.literal("TO_DATE('2026-03-31 10:15:00', 'YYYY-MM-DD HH24:MI:SS')"));
		assertTrue(SqlInsertParser.literal("USER").isEmpty());
	}

}
