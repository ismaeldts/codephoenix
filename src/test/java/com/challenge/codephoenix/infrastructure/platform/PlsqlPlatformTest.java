package com.challenge.codephoenix.infrastructure.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.challenge.codephoenix.domain.PlsqlModel;
import com.challenge.codephoenix.infrastructure.analysis.PlsqlStaticAnalyzer;

class PlsqlPlatformTest {

	private static final String CODE = """
			CREATE TABLE param (cod VARCHAR2(20) PRIMARY KEY, valor NUMBER);
			CREATE TABLE pedido (id NUMBER(10) PRIMARY KEY, total NUMBER(12,2));
			INSERT INTO param VALUES ('LIMITE', 100);
			INSERT INTO param VALUES ('IVA', 0.19);
			COMMIT;
			CREATE OR REPLACE PROCEDURE pr_aprobar (p_id IN NUMBER) IS
			    v_limite NUMBER;
			BEGIN
			    SELECT valor INTO v_limite FROM param WHERE cod = 'LIMITE';
			    UPDATE pedido SET total = LEAST(total, v_limite) WHERE id = p_id;
			END;
			/
			""";

	private final PlsqlModel model = new PlsqlStaticAnalyzer().analyze(CODE).plsql();

	@Test
	void addsReferenceDataAfterParamsAndBeforeCaseRows() {
		String input = "PARAM|P_ID|1\nROW|PEDIDO|ID=1|TOTAL=500";

		assertEquals("""
				PARAM|P_ID|1
				ROW|PARAM|COD=LIMITE|VALOR=100
				ROW|PARAM|COD=IVA|VALOR=0.19
				ROW|PEDIDO|ID=1|TOTAL=500""", PlsqlPlatform.withSeeds(model, input).strip());
	}

	@Test
	void caseRowsReplaceTheReferenceDataOfTheirTable() {
		String input = "PARAM|P_ID|1\nROW|PARAM|COD=LIMITE|VALOR=10\nROW|PEDIDO|ID=1|TOTAL=500";

		assertEquals(input, PlsqlPlatform.withSeeds(model, input).strip());
	}

}
