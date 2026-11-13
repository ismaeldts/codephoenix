package com.challenge.codephoenix.infrastructure.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.challenge.codephoenix.infrastructure.analysis.PlsqlStaticAnalyzer;

class ArtifactBundlerTest {

	private static final String DDL = """
			CREATE TABLE param (cod VARCHAR2(20) PRIMARY KEY, valor NUMBER);
			CREATE TABLE cliente (id NUMBER(10) PRIMARY KEY, nombre VARCHAR2(50) NOT NULL);
			CREATE TABLE pedido (id NUMBER(10) PRIMARY KEY, cliente_id NUMBER(10) NOT NULL REFERENCES cliente (id),
			  estado VARCHAR2(10) NOT NULL);
			CREATE SEQUENCE seq_log START WITH 1 INCREMENT BY 1;
			""";
	private static final String PARAMS = "INSERT INTO param VALUES ('LIMITE', 100);\nCOMMIT;\n";
	private static final String SPEC = """
			CREATE OR REPLACE PACKAGE pkg_pedidos AS
			    PROCEDURE pr_aprobar (p_id IN NUMBER);
			    PROCEDURE pr_lote (p_fecha IN DATE DEFAULT TRUNC(SYSDATE));
			END pkg_pedidos;
			/
			""";
	private static final String BODY = """
			CREATE OR REPLACE PACKAGE BODY pkg_pedidos AS
			    PROCEDURE pr_aprobar (p_id IN NUMBER) IS
			    BEGIN
			        UPDATE pedido SET estado = 'APROBADO' WHERE id = p_id;
			    END pr_aprobar;
			    PROCEDURE pr_lote (p_fecha IN DATE DEFAULT TRUNC(SYSDATE)) IS
			    BEGIN
			        FOR r IN (SELECT id FROM pedido) LOOP
			            pr_aprobar(r.id);
			        END LOOP;
			    END pr_lote;
			END pkg_pedidos;
			/
			""";
	private static final String TEST_DATA = """
			INSERT INTO cliente VALUES (1, 'ANA');
			INSERT INTO pedido VALUES (10, 1, 'NUEVO');
			""";
	private static final String RUN = "BEGIN pkg_pedidos.pr_lote(p_fecha => DATE '2026-03-31'); END;\n/\n";

	private final ArtifactBundler bundler = new ArtifactBundler(new PlsqlStaticAnalyzer());

	@Test
	void ordersAZipProjectAndTurnsItsTestDataIntoACase() throws IOException {
		byte[] zip = zip(
				"proyecto/src/04_body.sql", BODY, "proyecto/src/01_ddl.sql", DDL, "proyecto/src/03_spec.sql", SPEC,
				"proyecto/src/02_parametros.sql", PARAMS, "proyecto/pruebas/datos_prueba.sql", TEST_DATA,
				"proyecto/pruebas/ejecutar.sql", RUN, "__MACOSX/proyecto/src/._01_ddl.sql", "basura");

		ArtifactBundler.Bundle bundle = bundler.bundle(List.of(new ArtifactBundler.InputFile("proyecto.zip", zip)));

		assertEquals("proyecto.sql", bundle.sourceName());
		assertTrue(bundle.code().indexOf("CREATE TABLE param") < bundle.code().indexOf("INSERT INTO param"));
		assertTrue(bundle.code().indexOf("INSERT INTO param") < bundle.code().indexOf("CREATE OR REPLACE PACKAGE pkg_pedidos"));
		assertTrue(bundle.code().indexOf("PACKAGE pkg_pedidos") < bundle.code().indexOf("PACKAGE BODY"));
		assertTrue(!bundle.code().contains("INSERT INTO pedido"), "los datos de prueba no forman parte del artefacto");
		assertEquals(6, bundle.files().size());
		assertEquals(1, bundle.caseInputs().size());
		assertEquals("""
				CALL|PR_LOTE
				PARAM|P_FECHA|2026-03-31
				ROW|CLIENTE|ID=1|NOMBRE=ANA
				ROW|PEDIDO|ID=10|CLIENTE_ID=1|ESTADO=NUEVO""", bundle.caseInputs().getFirst());
	}

	@Test
	void classifiesFilesByContent() {
		assertEquals(ArtifactBundler.Role.ESQUEMA, ArtifactBundler.classify("a.sql", DDL));
		assertEquals(ArtifactBundler.Role.DATOS_REFERENCIA, ArtifactBundler.classify("src/02_parametros.sql", PARAMS));
		assertEquals(ArtifactBundler.Role.ESPECIFICACION, ArtifactBundler.classify("spec.sql", SPEC));
		assertEquals(ArtifactBundler.Role.PROGRAMA, ArtifactBundler.classify("body.sql", BODY));
		assertEquals(ArtifactBundler.Role.DATOS_PRUEBA, ArtifactBundler.classify("pruebas/datos.sql", TEST_DATA));
		assertEquals(ArtifactBundler.Role.EJECUCION, ArtifactBundler.classify("pruebas/run.sql", RUN));
	}

	private static byte[] zip(String... namesAndContents) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (int i = 0; i < namesAndContents.length; i += 2) {
				zip.putNextEntry(new ZipEntry(namesAndContents[i]));
				zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

}
