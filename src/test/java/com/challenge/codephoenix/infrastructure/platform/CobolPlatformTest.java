package com.challenge.codephoenix.infrastructure.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.challenge.codephoenix.domain.StaticAnalysis.RecordField;

class CobolPlatformTest {

	private final List<RecordField> layout = List.of(new RecordField("IN-MONTO", 1, 11, 2),
			new RecordField("IN-TASA-ANUAL", 12, 7, 4), new RecordField("IN-PLAZO", 19, 3, 0),
			new RecordField("IN-SCORE", 22, 3, 0));

	@Test
	void decodesFixedWidthRecordWithImpliedDecimals() {
		assertEquals("IN-MONTO=25000.50 · IN-TASA-ANUAL=15.0000 · IN-PLAZO=10 · IN-SCORE=600",
				CobolPlatform.describeRecord(layout, "000025000500150000010600"));
	}

	@Test
	void flagsNonNumericOrIncompleteFields() {
		assertEquals("IN-MONTO=1000.00 · IN-TASA-ANUAL=18.0000 · IN-PLAZO=12 · IN-SCORE=«70X» (no numérico o incompleto)",
				CobolPlatform.describeRecord(layout, "00000100000018000001270X"));
		assertEquals("IN-MONTO=«123» (no numérico o incompleto) · IN-TASA-ANUAL=«» (no numérico o incompleto) · "
				+ "IN-PLAZO=«» (no numérico o incompleto) · IN-SCORE=«» (no numérico o incompleto)",
				CobolPlatform.describeRecord(layout, "123"));
	}

}
