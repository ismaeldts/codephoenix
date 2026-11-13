package com.challenge.codephoenix.infrastructure.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.challenge.codephoenix.domain.SecurityReport;

class SecurityAnalyzerTest {

	private final SecurityAnalyzer analyzer = new SecurityAnalyzer();

	private static final String XML = """
			<?xml version="1.0" encoding="UTF-8"?>
			<BugCollection version="4.8.6">
			  <BugInstance type="PREDICTABLE_RANDOM" priority="2" category="SECURITY">
			    <LongMessage>Uso de un generador aleatorio predecible</LongMessage>
			    <Class classname="com.banco.app.Token"><SourceLine classname="com.banco.app.Token" sourcepath="com/banco/app/Token.java"/></Class>
			    <SourceLine classname="com.banco.app.Token" start="12" end="12" sourcepath="com/banco/app/Token.java"/>
			  </BugInstance>
			  <BugInstance type="EI_EXPOSE_REP" priority="2" category="MALICIOUS_CODE">
			    <LongMessage>Expone representación interna</LongMessage>
			    <SourceLine classname="com.banco.app.Plan" start="8" end="8" sourcepath="com/banco/app/Plan.java"/>
			  </BugInstance>
			  <BugInstance type="NP_NULL_ON_SOME_PATH" priority="1" category="CORRECTNESS">
			    <LongMessage>Posible NullPointerException</LongMessage>
			    <SourceLine classname="com.banco.app.Calc" start="30" end="30" sourcepath="com/banco/app/Calc.java"/>
			  </BugInstance>
			</BugCollection>
			""";

	@Test
	void classifiesSecurityAndCorrectnessIssues() {
		SecurityReport report = analyzer.fromSpotBugs(XML, Map.of(), "com.banco.app", "");

		assertTrue(report.executed());
		assertEquals(2, report.critical());
		assertEquals(1, report.minor());
		assertEquals("SAST:PREDICTABLE_RANDOM (SECURITY)", report.findings().getFirst().rule());
		assertEquals("src/main/java/com/banco/app/Token.java", report.findings().getFirst().file());
		assertEquals(12, report.findings().getFirst().line());
	}

	@Test
	void describesFindingsWhenSpotBugsOmitsTheMessage() {
		String xml = """
				<BugCollection>
				  <BugInstance type="DM_CONVERT_CASE" priority="3" category="I18N">
				    <Method classname="demo.Entrada" name="columnas"/>
				    <SourceLine classname="demo.Entrada" start="50" sourcepath="demo/Entrada.java"/>
				  </BugInstance>
				  <BugInstance type="SOME_NEW_PATTERN" priority="3" category="STYLE">
				    <SourceLine classname="demo.Entrada" start="7" sourcepath="demo/Entrada.java"/>
				  </BugInstance>
				</BugCollection>
				""";

		SecurityReport report = analyzer.fromSpotBugs(xml, Map.of(), "demo", "");

		assertEquals("Conversión a mayúsculas/minúsculas sin Locale explícito (método columnas)",
				report.findings().get(0).message());
		assertTrue(report.findings().get(1).message().startsWith("Patrón SOME_NEW_PATTERN detectado por"));
	}

	@Test
	void cleanReportHasNoFindings() {
		SecurityReport report = analyzer.fromSpotBugs("<BugCollection/>", Map.of(), "x", "");

		assertEquals(0, report.critical() + report.major() + report.minor());
	}

	@Test
	void listsOnlyThirdPartyProductionImports() {
		Map<String, String> files = Map.of(
				"src/main/java/com/banco/app/A.java", "import java.math.BigDecimal;\nimport com.banco.app.domain.X;\nimport org.apache.commons.lang3.StringUtils;",
				"src/test/java/com/banco/app/ATest.java", "import org.junit.jupiter.api.Test;");

		assertEquals(List.of("org.apache.commons.lang3"), analyzer.productionDependencies(files, "com.banco.app"));
	}

}
