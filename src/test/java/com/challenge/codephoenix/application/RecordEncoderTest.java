package com.challenge.codephoenix.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.challenge.codephoenix.domain.StaticAnalysis.RecordField;

class RecordEncoderTest {

	private final List<RecordField> layout = List.of(new RecordField("IN-MONTO", 1, 11, 2),
			new RecordField("IN-TASA-ANUAL", 12, 7, 4), new RecordField("IN-PLAZO", 19, 3, 0),
			new RecordField("IN-SCORE", 22, 3, 0));

	@Test
	void encodesImpliedDecimalsWithZeroPadding() {
		assertEquals("000100000000180000012700", RecordEncoder.encode(layout,
				Map.of("IN-MONTO", "100000.00", "IN-TASA-ANUAL", "18", "IN-PLAZO", "12", "IN-SCORE", "700")).orElseThrow());
	}

	@Test
	void rejectsValuesThatDoNotFitThePicture() {
		assertTrue(RecordEncoder.encode(layout,
				Map.of("IN-MONTO", "100000.00", "IN-TASA-ANUAL", "18", "IN-PLAZO", "1200", "IN-SCORE", "700")).isEmpty());
		assertTrue(RecordEncoder.encode(layout,
				Map.of("IN-MONTO", "100.001", "IN-TASA-ANUAL", "18", "IN-PLAZO", "12", "IN-SCORE", "700")).isEmpty());
	}

	@Test
	void rejectsMissingFields() {
		assertTrue(RecordEncoder.encode(layout, Map.of("IN-MONTO", "100000.00")).isEmpty());
	}

}
