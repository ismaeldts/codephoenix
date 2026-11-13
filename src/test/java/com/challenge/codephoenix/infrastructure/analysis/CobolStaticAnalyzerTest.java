package com.challenge.codephoenix.infrastructure.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.challenge.codephoenix.domain.StaticAnalysis;

class CobolStaticAnalyzerTest {

	private final CobolStaticAnalyzer analyzer = new CobolStaticAnalyzer();

	private StaticAnalysis sample() throws IOException {
		return analyzer.analyze(new ClassPathResource("samples/AMORTIZA.cbl").getContentAsString(StandardCharsets.UTF_8));
	}

	@Test
	void inventoriesParagraphsAndDependencies() throws IOException {
		StaticAnalysis analysis = sample();

		assertEquals("AMORTIZA", analysis.programId());
		assertEquals(List.of("0000-PRINCIPAL", "1000-VALIDAR", "1000-VALIDAR-EXIT", "2000-AJUSTAR-TASA",
				"3000-CALCULAR-CUOTA", "4000-GENERAR-TABLA", "4100-IMPRIMIR-DETALLE", "5000-TOTALES",
				"9000-CALCULAR-MORA"), analysis.paragraphs().stream().map(StaticAnalysis.Paragraph::name).toList());
		assertEquals(4, analysis.goToCount());
		assertTrue(analyzer.unresolvedTargets(analysis).isEmpty());
	}

	@Test
	void detectsDeadCode() throws IOException {
		assertEquals(List.of("9000-CALCULAR-MORA"), sample().deadParagraphs());
	}

	@Test
	void mapsPackedDecimalsToExactBigDecimal() throws IOException {
		StaticAnalysis.DataItem monthlyRate = sample().dataItems()
			.stream()
			.filter(d -> d.name().equals("WS-TASA-MENSUAL"))
			.findFirst()
			.orElseThrow();

		assertEquals("COMP-3", monthlyRate.usage());
		assertEquals("BigDecimal(precision=11, scale=10)", monthlyRate.javaType());
	}

	@Test
	void derivesInputRecordLayoutFromAccept() throws IOException {
		assertEquals(List.of(new StaticAnalysis.RecordField("IN-MONTO", 1, 11, 2),
				new StaticAnalysis.RecordField("IN-TASA-ANUAL", 12, 7, 4), new StaticAnalysis.RecordField("IN-PLAZO", 19, 3, 0),
				new StaticAnalysis.RecordField("IN-SCORE", 22, 3, 0)), sample().inputRecord());
	}

	@Test
	void mapsPictureClauses() {
		assertEquals("int", CobolStaticAnalyzer.javaType("9(3)"));
		assertEquals("long", CobolStaticAnalyzer.javaType("9(12)"));
		assertEquals("BigDecimal(precision=7, scale=4)", CobolStaticAnalyzer.javaType("S9(3)V9(4)"));
		assertEquals("String(40)", CobolStaticAnalyzer.javaType("X(40)"));
		assertEquals("String (formato editado)", CobolStaticAnalyzer.javaType("Z(8)9.99"));
	}

	@Test
	void measuresValidationComplexity() throws IOException {
		StaticAnalysis.Paragraph validation = sample().paragraphs()
			.stream()
			.filter(p -> p.name().equals("1000-VALIDAR"))
			.findFirst()
			.orElseThrow();

		// 5 IF + 2 OR
		assertEquals(8, validation.complexity());
	}

}
