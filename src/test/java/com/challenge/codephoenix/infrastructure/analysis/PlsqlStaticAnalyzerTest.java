package com.challenge.codephoenix.infrastructure.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.challenge.codephoenix.domain.PlsqlModel;
import com.challenge.codephoenix.domain.StaticAnalysis;

class PlsqlStaticAnalyzerTest {

	private final PlsqlStaticAnalyzer analyzer = new PlsqlStaticAnalyzer();

	private String sampleCode() throws IOException {
		return new ClassPathResource("samples/LIQUIDA_SINIESTRO.sql").getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void parsesEntryPointSignature() throws IOException {
		PlsqlModel model = analyzer.analyze(sampleCode()).plsql();

		assertEquals("LIQUIDA_SINIESTRO", model.entryPoint());
		assertEquals("PROCEDURE", model.kind());
		assertEquals(List.of(new PlsqlModel.Param("P_SINIESTRO_ID", "IN", "NUMBER"),
				new PlsqlModel.Param("P_MONTO_PAGAR", "OUT", "NUMBER"), new PlsqlModel.Param("P_ESTADO", "OUT", "VARCHAR2")),
				model.params());
	}

	@Test
	void parsesSchemaAndTableUsage() throws IOException {
		PlsqlModel model = analyzer.analyze(sampleCode()).plsql();

		assertEquals(List.of("POLIZAS", "SINIESTROS", "PAGOS_SINIESTRO"), model.schema().stream().map(PlsqlModel.Table::name).toList());
		PlsqlModel.Column coinsurance = model.table("POLIZAS").orElseThrow().column("PORC_COASEGURO").orElseThrow();
		assertEquals("NUMBER(5,2)", coinsurance.type());
		assertEquals(false, coinsurance.nullable());
		assertTrue(model.table("SINIESTROS").orElseThrow().column("MONTO_LIQUIDADO").orElseThrow().nullable());
		assertEquals(List.of("SINIESTROS", "POLIZAS"), model.tablesRead());
		assertEquals(List.of("PAGOS_SINIESTRO", "SINIESTROS"), model.tablesWritten());
		assertTrue(model.missingTables().isEmpty());
		assertTrue(model.nonDeterministic().isEmpty());
	}

	@Test
	void findsUnitsDeadCodeAndGoto() throws IOException {
		StaticAnalysis analysis = analyzer.analyze(sampleCode());

		assertEquals(List.of("LIQUIDA_SINIESTRO", "CALCULA_IVA"),
				analysis.paragraphs().stream().map(StaticAnalysis.Paragraph::name).toList());
		assertEquals(List.of("CALCULA_IVA"), analysis.deadParagraphs());
		assertEquals(3, analysis.goToCount());
		assertTrue(analyzer.unresolvedTargets(analysis).isEmpty());
	}

	@Test
	void mapsDeclarationsToJavaTypes() throws IOException {
		StaticAnalysis analysis = analyzer.analyze(sampleCode());

		StaticAnalysis.DataItem base = analysis.dataItems().stream().filter(d -> d.name().equals("V_BASE")).findFirst().orElseThrow();
		assertEquals("NUMBER(12,2)", base.picture());
		assertEquals("BigDecimal(precision=12, scale=2, redondeo HALF_UP al asignar)", base.javaType());
		StaticAnalysis.DataItem retention = analysis.dataItems().stream().filter(d -> d.name().equals("V_RETENCION")).findFirst().orElseThrow();
		assertEquals("0", retention.value());
		assertEquals("record de SINIESTROS", analysis.dataItems().stream().filter(d -> d.name().equals("V_SIN")).findFirst().orElseThrow().javaType());
	}

	@Test
	void reportsMissingTablesExternalCallsAndNonDeterminism() {
		String code = """
				CREATE OR REPLACE FUNCTION CALCULA_PRIMA (p_cliente IN NUMBER) RETURN NUMBER IS
				    v_total NUMBER;
				BEGIN
				    SELECT SUM(prima) INTO v_total FROM coberturas WHERE cliente_id = p_cliente AND fecha < SYSDATE;
				    AUDITORIA.REGISTRAR(p_cliente);
				    RETURN v_total;
				END CALCULA_PRIMA;
				/
				""";

		StaticAnalysis analysis = analyzer.analyze(code);

		assertEquals("FUNCTION", analysis.plsql().kind());
		assertEquals("NUMBER", analysis.plsql().returnType());
		assertEquals(List.of("COBERTURAS"), analysis.plsql().missingTables());
		assertEquals("SYSDATE", analysis.plsql().nonDeterministic().getFirst().construct());
		assertEquals(List.of("AUDITORIA.REGISTRAR"), analyzer.unresolvedTargets(analysis));
	}

	@Test
	void splitsSchemaFromProgram() throws IOException {
		PlsqlStaticAnalyzer.Source source = analyzer.split(sampleCode());

		assertTrue(source.schemaDdl().contains("CREATE TABLE PAGOS_SINIESTRO"));
		assertTrue(source.program().startsWith("CREATE OR REPLACE PROCEDURE LIQUIDA_SINIESTRO"));
	}

}
