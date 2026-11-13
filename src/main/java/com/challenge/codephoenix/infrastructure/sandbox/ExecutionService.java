package com.challenge.codephoenix.infrastructure.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.TestCase;
import com.challenge.codephoenix.domain.TestRunReport;

/**
 * Operaciones de alto nivel sobre el sandbox: obtener el Golden Master del
 * COBOL original, compilar y ejecutar el código moderno, y correr la suite
 * de pruebas con cobertura.
 */
@Component
public class ExecutionService {

	public record RunOutcome(boolean ok, String log, Map<String, List<String>> outputs) {
	}

	private static final Pattern JUNIT_SUMMARY = Pattern.compile("\\[\\s*(\\d+) tests (successful|failed|found)\\s*]");

	private final DockerSandbox sandbox;
	private final OracleSandbox oracle;

	public ExecutionService(DockerSandbox sandbox, OracleSandbox oracle) {
		this.sandbox = sandbox;
		this.oracle = oracle;
	}

	// ------------------------------------------------------------- PL/SQL

	/** Resultado de crear el esquema y compilar el programa PL/SQL en Oracle. */
	public record PlsqlCompilation(boolean ok, List<String> compileErrors, List<String> objects, String log) {
	}

	/** Crea un esquema limpio, ejecuta el DDL y compila el programa; devuelve los errores de compilación. */
	public PlsqlCompilation compilePlsql(Path jobDir, String jobId, String schemaDdl, String program,
			List<String> expectedTables, String entryObject) throws IOException, InterruptedException {
		Path dir = jobDir.resolve("plsql");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("setup.sql"), PlsqlHarness.setupScript(schemaDdl, program), StandardCharsets.UTF_8);
		String script = oracleUserScript(jobId) + """
				sqlplus -s -L "$CP_USER/\\"$CP_PASS\\"@//localhost/FREEPDB1" @setup.sql > setup.log 2>&1
				cat setup.log
				%s
				""".formatted(dropUserScript(jobId));
		OracleSandbox.Result result = oracle.exec(jobDir, script);
		List<String> errors = new ArrayList<>();
		List<String> objects = new ArrayList<>();
		for (String line : result.output().split("\r?\n")) {
			if (line.startsWith("COMPILE_ERR|")) {
				errors.add(line.substring("COMPILE_ERR|".length()));
			}
			else if (line.startsWith("OBJ|")) {
				objects.add(line.substring(4));
			}
			else if (line.matches("^(ORA|SP2|PLS)-\\d+:.*")) {
				errors.add(line.strip());
			}
		}
		String entry = entryObject.contains(".") ? entryObject.substring(0, entryObject.indexOf('.')) : entryObject;
		boolean entryValid = objects.stream().anyMatch(o -> o.contains("|" + entry + "|VALID"));
		boolean tablesCreated = expectedTables.stream().allMatch(t -> objects.stream().anyMatch(o -> o.equals("TABLE|" + t + "|VALID")));
		if (!entryValid) {
			errors.add("La rutina " + entryObject + " no quedó compilada (VALID) en Oracle");
		}
		if (!tablesCreated) {
			errors.add("No se crearon todas las tablas del esquema: " + expectedTables);
		}
		return new PlsqlCompilation(result.succeeded() && errors.isEmpty(), errors, objects, result.output());
	}

	/** Ejecuta cada caso contra el programa PL/SQL original en Oracle y guarda su salida (Golden Master). */
	public RunOutcome runLegacyPlsql(Path jobDir, String jobId, com.challenge.codephoenix.domain.PlsqlModel model,
			String schemaDdl, String program, List<TestCase> cases) throws IOException, InterruptedException {
		writeCases(jobDir, cases);
		Path dir = jobDir.resolve("plsql");
		Path caseDir = dir.resolve("cases");
		Files.createDirectories(caseDir);
		try (var existing = Files.list(caseDir)) {
			for (Path p : existing.toList()) {
				Files.delete(p);
			}
		}
		Files.writeString(dir.resolve("setup.sql"), PlsqlHarness.setupScript(schemaDdl, program), StandardCharsets.UTF_8);
		for (TestCase testCase : cases) {
			String sql = PlsqlHarness.caseScript(model, PlsqlHarness.parse(testCase.input()));
			Files.writeString(caseDir.resolve(testCase.id() + ".sql"), sql, StandardCharsets.UTF_8);
		}
		String script = oracleUserScript(jobId) + """
				sqlplus -s -L "$CP_USER/\\"$CP_PASS\\"@//localhost/FREEPDB1" @setup.sql > setup.log 2>&1
				if grep -q '^COMPILE_ERR' setup.log; then cat setup.log; %s; exit 40; fi
				rm -rf ../golden && mkdir -p ../golden
				for f in cases/*.sql; do
				  id=$(basename "$f" .sql)
				  timeout 60 sqlplus -s -L "$CP_USER/\\"$CP_PASS\\"@//localhost/FREEPDB1" @"$f" > "../golden/$id.out" 2>&1
				done
				%s
				echo LEGACY_OK
				""".formatted(dropUserScript(jobId), dropUserScript(jobId));
		OracleSandbox.Result result = oracle.exec(jobDir, script);
		boolean ok = result.succeeded() && result.output().contains("LEGACY_OK");
		return new RunOutcome(ok, result.output(), ok ? readOutputs(jobDir.resolve("golden"), cases) : Map.of());
	}

	/** Crea (o recrea) el usuario de Oracle de la migración y deja sus credenciales en CP_USER y CP_PASS. */
	private static String oracleUserScript(String jobId) {
		String user = "CP_" + jobId.toUpperCase().replaceAll("[^A-Z0-9]", "");
		String password = OracleSandbox.randomSecret();
		return """
				set -u
				cd "$(dirname "$0")"
				export CP_USER=%s CP_PASS=%s
				sqlplus -s -L "system/${ORACLE_PASSWORD}@//localhost/FREEPDB1" > user.log 2>&1 <<SQL
				WHENEVER SQLERROR EXIT FAILURE
				DROP USER IF EXISTS $CP_USER CASCADE;
				CREATE USER $CP_USER IDENTIFIED BY "$CP_PASS" QUOTA UNLIMITED ON USERS;
				GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE SEQUENCE, CREATE VIEW, CREATE TRIGGER, CREATE TYPE, CREATE SYNONYM TO $CP_USER;
				EXIT
				SQL
				if [ $? -ne 0 ]; then echo "No se pudo crear el esquema Oracle"; cat user.log; exit 41; fi
				""".formatted(user, password);
	}

	private static String dropUserScript(String jobId) {
		String user = "CP_" + jobId.toUpperCase().replaceAll("[^A-Z0-9]", "");
		return "echo 'DROP USER IF EXISTS " + user + " CASCADE;' | sqlplus -s -L \"system/${ORACLE_PASSWORD}@//localhost/FREEPDB1\" > /dev/null 2>&1";
	}

	/** Verifica que el artefacto legacy esté completo y sea compilable (copybooks resueltos, sintaxis válida). */
	public RunOutcome checkLegacy(Path jobDir, String programFile) throws IOException, InterruptedException {
		String script = """
				cobc -fsyntax-only legacy/%s 2>&1 && echo SYNTAX_OK
				""".formatted(programFile);
		DockerSandbox.Result result = sandbox.run(jobDir, script);
		return new RunOutcome(result.succeeded() && result.output().contains("SYNTAX_OK"), result.output(), Map.of());
	}

	/** Compila el programa COBOL original con GnuCOBOL y ejecuta cada caso. */
	public RunOutcome runLegacy(Path jobDir, String programFile, List<TestCase> cases)
			throws IOException, InterruptedException {
		writeCases(jobDir, cases);
		String script = """
				set -u
				rm -rf golden && mkdir -p golden reports
				cobc -x -o /tmp/legacy-prog legacy/%s > reports/cobc.log 2>&1 || { cat reports/cobc.log; exit 10; }
				for f in cases/*.in; do
				  id=$(basename "$f" .in)
				  timeout 20 /tmp/legacy-prog < "$f" > "golden/$id.out" 2>&1
				done
				echo LEGACY_OK
				""".formatted(programFile);
		DockerSandbox.Result result = sandbox.run(jobDir, script);
		boolean ok = result.succeeded() && result.output().contains("LEGACY_OK");
		return new RunOutcome(ok, result.output(), ok ? readOutputs(jobDir.resolve("golden"), cases) : Map.of());
	}

	/** Compila el código generado (sin dependencias externas) y lo ejecuta con los mismos casos. */
	public RunOutcome runModern(Path jobDir, String mainClass, List<TestCase> cases)
			throws IOException, InterruptedException {
		writeCases(jobDir, cases);
		String script = """
				set -u
				rm -rf modern build/classes && mkdir -p modern build/classes reports
				find generated/src/main/java -name '*.java' > /tmp/sources.txt
				javac -encoding UTF-8 -Xlint:all -d build/classes @/tmp/sources.txt > reports/javac.log 2>&1 \\
				  || { echo COMPILE_FAILED; cat reports/javac.log; exit 20; }
				for f in cases/*.in; do
				  id=$(basename "$f" .in)
				  timeout 20 java -XX:TieredStopAtLevel=1 -Xshare:auto -cp build/classes %s < "$f" > "modern/$id.out" 2>&1
				done
				echo MODERN_OK
				""".formatted(mainClass);
		DockerSandbox.Result result = sandbox.run(jobDir, script);
		boolean ok = result.succeeded() && result.output().contains("MODERN_OK");
		return new RunOutcome(ok, result.output(), ok ? readOutputs(jobDir.resolve("modern"), cases) : Map.of());
	}

	/** Ejecuta la suite JUnit 5 generada con el agente de JaCoCo para medir la cobertura. */
	public TestRunReport runUnitTests(Path jobDir) throws IOException, InterruptedException {
		String script = """
				set -u
				rm -rf build/test-classes reports/junit reports/coverage-html && mkdir -p build/test-classes reports
				find generated/src/test/java -name '*.java' > /tmp/tests.txt
				javac -encoding UTF-8 -cp build/classes:/opt/tools/junit-console.jar -d build/test-classes @/tmp/tests.txt \\
				  > reports/javac-tests.log 2>&1 || { echo TEST_COMPILE_FAILED; cat reports/javac-tests.log; exit 30; }
				java -javaagent:/opt/tools/jacocoagent.jar=destfile=reports/jacoco.exec \\
				  -jar /opt/tools/junit-console.jar execute --disable-banner --details=summary \\
				  --class-path build/test-classes:build/classes --scan-class-path \\
				  --reports-dir reports/junit > reports/junit.log 2>&1
				cat reports/junit.log
				java -jar /opt/tools/jacococli.jar report reports/jacoco.exec --classfiles build/classes \\
				  --sourcefiles generated/src/main/java --csv reports/coverage.csv --html reports/coverage-html > /dev/null 2>&1
				echo TESTS_DONE
				""";
		DockerSandbox.Result result = sandbox.run(jobDir, script);
		String output = result.output();
		if (output.contains("TEST_COMPILE_FAILED")) {
			return new TestRunReport(false, 0, 0, 0, 0, 0, output);
		}
		int found = 0;
		int succeeded = 0;
		int failed = 0;
		Matcher m = JUNIT_SUMMARY.matcher(output);
		while (m.find()) {
			int value = Integer.parseInt(m.group(1));
			switch (m.group(2)) {
				case "found" -> found = value;
				case "successful" -> succeeded = value;
				default -> failed = value;
			}
		}
		double[] coverage = readCoverage(jobDir.resolve("reports/coverage.csv"));
		return new TestRunReport(true, found, succeeded, failed, coverage[0], coverage[1], output);
	}

	private static void writeCases(Path jobDir, List<TestCase> cases) throws IOException {
		Path dir = jobDir.resolve("cases");
		Files.createDirectories(dir);
		try (var existing = Files.list(dir)) {
			for (Path p : existing.toList()) {
				Files.delete(p);
			}
		}
		for (TestCase testCase : cases) {
			Files.writeString(dir.resolve(testCase.id() + ".in"), testCase.input() + "\n", StandardCharsets.UTF_8);
		}
	}

	private static Map<String, List<String>> readOutputs(Path dir, List<TestCase> cases) throws IOException {
		Map<String, List<String>> outputs = new LinkedHashMap<>();
		for (TestCase testCase : cases) {
			Path file = dir.resolve(testCase.id() + ".out");
			List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
			outputs.put(testCase.id(), lines.stream().filter(l -> !l.isBlank()).toList());
		}
		return outputs;
	}

	/** Lee el CSV de JaCoCo y devuelve {cobertura de líneas, cobertura de ramas} en porcentaje. */
	private static double[] readCoverage(Path csv) throws IOException {
		if (!Files.exists(csv)) {
			return new double[] { 0, 0 };
		}
		long lineMissed = 0;
		long lineCovered = 0;
		long branchMissed = 0;
		long branchCovered = 0;
		List<String> rows = new ArrayList<>(Files.readAllLines(csv, StandardCharsets.UTF_8));
		rows.removeFirst();
		for (String row : rows) {
			String[] c = row.split(",");
			branchMissed += Long.parseLong(c[5]);
			branchCovered += Long.parseLong(c[6]);
			lineMissed += Long.parseLong(c[7]);
			lineCovered += Long.parseLong(c[8]);
		}
		return new double[] { percent(lineCovered, lineMissed), percent(branchCovered, branchMissed) };
	}

	private static double percent(long covered, long missed) {
		long total = covered + missed;
		return total == 0 ? 100.0 : Math.round(covered * 1000.0 / total) / 10.0;
	}

}
