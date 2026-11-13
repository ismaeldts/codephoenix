package com.challenge.codephoenix.infrastructure.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.application.GateFailedException;
import com.challenge.codephoenix.application.LegacyPlatform;
import com.challenge.codephoenix.domain.MigrationJob;
import com.challenge.codephoenix.domain.Phase;
import com.challenge.codephoenix.domain.PlsqlModel;
import com.challenge.codephoenix.domain.StaticAnalysis;
import com.challenge.codephoenix.domain.TestCase;
import com.challenge.codephoenix.infrastructure.ai.DeveloperAgent;
import com.challenge.codephoenix.infrastructure.ai.QaAgent;
import com.challenge.codephoenix.infrastructure.ai.SchemaAgent;
import com.challenge.codephoenix.infrastructure.analysis.PlsqlStaticAnalyzer;
import com.challenge.codephoenix.infrastructure.sandbox.ExecutionService;
import com.challenge.codephoenix.infrastructure.sandbox.OracleSandbox;
import com.challenge.codephoenix.infrastructure.sandbox.PlsqlHarness;

/**
 * PL/SQL: se ejecuta en Oracle Database Free. Cada caso define los parámetros
 * de entrada y las filas iniciales de las tablas; la salida incluye los
 * parámetros de salida (o el error) y el estado final de las tablas modificadas.
 */
@Component
public class PlsqlPlatform implements LegacyPlatform {

	private final PlsqlStaticAnalyzer analyzer;
	private final ExecutionService execution;
	private final OracleSandbox oracle;
	private final SchemaAgent schemaAgent;

	public PlsqlPlatform(PlsqlStaticAnalyzer analyzer, ExecutionService execution, OracleSandbox oracle,
			SchemaAgent schemaAgent) {
		this.analyzer = analyzer;
		this.execution = execution;
		this.oracle = oracle;
		this.schemaAgent = schemaAgent;
	}

	@Override
	public String language() {
		return "PL/SQL";
	}

	@Override
	public String runtimeDescription() {
		return "Oracle Database 23ai Free en una red interna sin acceso a Internet";
	}

	@Override
	public String unitTerm() {
		return "procedimiento o función PL/SQL";
	}

	@Override
	public String verifyArtifact(MigrationJob job, Path jobDir) {
		if (analyzer.split(job.getOriginalCode()).program().isBlank()) {
			throw new GateFailedException(Phase.INGESTA, "No se encontró ningún CREATE PROCEDURE, FUNCTION o PACKAGE. "
					+ "Carga el código fuente de la rutina (y, si es posible, el DDL de sus tablas).");
		}
		if (oracle.availability() == OracleSandbox.Availability.SIN_IMAGEN) {
			throw new GateFailedException(Phase.INGESTA, "El sandbox de Oracle no está instalado. Descarga la imagen con: "
					+ "docker pull gvenzl/oracle-free:23-slim-faststart");
		}
		return "PL/SQL identificado; la compilación en Oracle se verifica en la fase 2, con el esquema completo";
	}

	@Override
	public StaticAnalysis analyze(MigrationJob job) {
		return analyzer.analyze(job.getMaskedCode());
	}

	@Override
	public List<String> unresolvedDependencies(StaticAnalysis analysis) {
		return analyzer.unresolvedTargets(analysis);
	}

	@Override
	public void prepareExecution(MigrationJob job, Path jobDir) throws IOException, InterruptedException {
		PlsqlModel model = job.getAnalysis().plsql();
		if (model == null || model.entryPoint() == null) {
			throw new GateFailedException(Phase.ANALISIS, "No se pudo identificar la rutina de entrada (PROCEDURE o FUNCTION).");
		}
		String inferredDdl = "";
		List<String> inferred = List.of();
		if (!model.missingTables().isEmpty()) {
			inferred = model.missingTables();
			job.log(Phase.ANALISIS, "WARN", "El artefacto no incluye el DDL de " + String.join(", ", inferred)
					+ ": el Analista de datos infiere el esquema a partir del uso en el código");
			SchemaAgent.InferredSchema schema = schemaAgent.infer(job, inferred);
			inferredDdl = schema.ddl() == null ? "" : schema.ddl().strip();
			for (String assumption : schema.assumptions() == null ? List.<String>of() : schema.assumptions()) {
				job.log(Phase.ANALISIS, "WARN", "Supuesto de esquema: " + assumption);
			}
			StaticAnalysis completed = analyzer.analyze(job.getMaskedCode(), inferredDdl);
			job.analyzed(completed);
			model = completed.plsql();
			if (!model.missingTables().isEmpty()) {
				throw new GateFailedException(Phase.ANALISIS,
						"El esquema inferido no cubre las tablas: " + String.join(", ", model.missingTables()));
			}
		}
		String maskedDdl = analyzer.split(job.getMaskedCode()).schemaDdl();
		job.schemaResolved((maskedDdl.strip() + "\n\n" + inferredDdl).strip(), inferred, inferredDdl);

		PlsqlStaticAnalyzer.Source source = analyzer.split(job.getOriginalCode());
		List<String> tables = model.schema().stream().map(PlsqlModel.Table::name).toList();
		job.log(Phase.ANALISIS, "INFO", "Creando el esquema y compilando " + model.entryPoint() + " en Oracle");
		ExecutionService.PlsqlCompilation compilation = execution.compilePlsql(jobDir, job.getId(),
				executableDdl(source, job), source.program(), tables, model.entryPoint());
		if (!compilation.ok()) {
			throw new GateFailedException(Phase.ANALISIS, "El programa no compila en Oracle:\n"
					+ String.join("\n", compilation.compileErrors().stream().limit(15).toList()));
		}
		job.log(Phase.ANALISIS, "OK", "Oracle: " + tables.size() + " tablas creadas y " + model.entryPoint()
				+ " compilado (VALID)");
		model.nonDeterministic()
			.forEach(n -> job.log(Phase.ANALISIS, "WARN", "Línea " + n.line() + ": " + n.construct()
					+ " no es determinista; el Golden Master puede variar entre ejecuciones"));
	}

	@Override
	public String caseInstructions(MigrationJob job) {
		PlsqlModel model = job.getAnalysis().plsql();
		String seeds = seedSummary(model);
		return """
				Rutinas públicas que se pueden invocar (la primera es la rutina por defecto):
				%s
				Esquema de datos:
				%s
				Tablas que lee: %s · tablas que modifica: %s
				%s
				Para cada caso:
				- `routine`: nombre corto de la rutina a invocar (vacío = la rutina por defecto). Cubre TODAS las rutinas
				  públicas; las de proceso por lotes con varios registros a la vez.
				- `params`: valores de los parámetros de ENTRADA de esa rutina por su nombre exacto (números con punto,
				  fechas yyyy-MM-dd). Un parámetro omitido toma su valor DEFAULT o NULL.
				- `fixtures`: filas iniciales por tabla (nombre exacto de la tabla → lista de filas; cada fila es un
				  objeto columna → valor). Incluye las filas de TODAS las tablas que la rutina lee (salvo las de datos de
				  referencia, que se cargan solas), con todas las columnas NOT NULL, y respeta las claves foráneas. Usa
				  las filas MÍNIMAS que necesita el caso (un lote de 3 a 5 registros basta) y omite las columnas
				  anulables que la rutina no usa. Para
				  NULL usa el texto "\\N".
				- Deja `fields` e `input` vacíos.
				- Incluye casos donde falten filas (errores por datos inexistentes) y valores frontera exactos de fechas
				  (por ejemplo el día justo en que vence un plazo) e importes.
				""".formatted(routinesText(model), schemaText(model), String.join(", ", model.tablesRead()),
				String.join(", ", model.tablesWritten()), seeds.isEmpty() ? "" : "\n" + seeds + "\n");
	}

	/** Descripción de los datos de referencia que se añaden automáticamente a cada caso. */
	private static String seedSummary(PlsqlModel model) {
		if (model.seedRows().isEmpty()) {
			return "";
		}
		Map<String, Long> perTable = model.seedRows()
			.stream()
			.collect(Collectors.groupingBy(PlsqlModel.SeedRow::table, java.util.LinkedHashMap::new, Collectors.counting()));
		String sample = model.seedRows()
			.stream()
			.limit(30)
			.map(r -> "  " + r.table() + " " + r.values())
			.collect(Collectors.joining("\n"));
		return "Datos de referencia del artefacto (se añaden SOLOS a cada caso; no los repitas salvo que quieras "
				+ "cambiar esa tabla completa): " + perTable + "\n" + sample;
	}

	@Override
	public List<TestCase> toTestCases(MigrationJob job, List<QaAgent.CaseDraft> drafts) {
		PlsqlModel model = job.getAnalysis().plsql();
		List<TestCase> cases = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (QaAgent.CaseDraft draft : drafts) {
			String input = draft.input() != null && !draft.input().isBlank() ? draft.input().strip() : encode(model, draft);
			addIfValid(job, model, cases, seen, input, draft.description(), draft.kind() == null ? "NOMINAL" : draft.kind(),
					draft.ruleIds() == null ? List.of() : draft.ruleIds());
		}
		for (String block : String.join("\n", job.getCaseInputs()).split("\\r?\\n\\s*\\r?\\n")) {
			if (!block.isBlank()) {
				addIfValid(job, model, cases, seen, block.strip(), "Caso aportado por el usuario", "USUARIO", List.of());
			}
		}
		return cases;
	}

	@Override
	public ExecutionService.RunOutcome runLegacy(MigrationJob job, Path jobDir, List<TestCase> cases)
			throws IOException, InterruptedException {
		PlsqlStaticAnalyzer.Source source = analyzer.split(job.getOriginalCode());
		return execution.runLegacyPlsql(jobDir, job.getId(), job.getAnalysis().plsql(), executableDdl(source, job),
				source.program(), cases);
	}

	@Override
	public String architectureHint(MigrationJob job) {
		PlsqlModel model = job.getAnalysis().plsql();
		return """
				- Paquetes: <basePackage>.domain.model, <basePackage>.domain.service, <basePackage>.application.port.in,
				  <basePackage>.application.port.out, <basePackage>.application.service, <basePackage>.adapter.in.cli,
				  <basePackage>.adapter.out.persistence.
				- El acceso a datos del legacy (tablas %s) se convierte en puertos de salida (repositorios) en
				  application.port.out, con implementaciones en memoria en adapter.out.persistence. El dominio no
				  conoce SQL.
				- Existirá un adaptador CLI de caracterización que carga las filas iniciales en los repositorios en
				  memoria, invoca el caso de uso y vuelca el estado final de las tablas modificadas, para demostrar la
				  equivalencia contra Oracle. Un adaptador JDBC de producción implementaría los mismos puertos.
				- Contrato REST (OpenAPI) para la coexistencia con el sistema legado mediante el patrón Strangler Fig.
				""".formatted(String.join(", ", model.schema().stream().map(PlsqlModel.Table::name).toList()));
	}

	@Override
	public String ioContract(MigrationJob job) {
		PlsqlModel model = job.getAnalysis().plsql();
		String written = model.tablesWritten().stream()
			.map(t -> model.table(t)
				.map(table -> t + "(" + table.columns().stream().map(PlsqlModel.Column::name).collect(Collectors.joining(", ")) + ")")
				.orElse(t))
			.collect(Collectors.joining("; "));
		String sequences = model.sequences().isEmpty() ? "(no hay)" : model.sequences()
			.stream()
			.map(s -> s.name() + " empieza en " + s.start() + " e incrementa " + s.increment())
			.collect(Collectors.joining("; "));
		return """
				Adaptador CLI de caracterización `%s` (reproduce el contrato con el que se ejecutó el programa en Oracle):
				- `public static java.util.List<String> process(java.util.List<String> inputLines)`
				- `public static void main(String[] args)`: lee TODAS las líneas de System.in, llama a process e imprime
				  cada línea devuelta con System.out.println.

				Rutinas públicas (la primera es la rutina por defecto):
				%s

				Entrada (una instrucción por línea):
				  CALL|<RUTINA>                      opcional: nombre corto de la rutina a invocar
				  PARAM|<PARAMETRO>|<valor>          parámetros de entrada de esa rutina
				  ROW|<TABLA>|<COLUMNA>=<valor>|...  filas iniciales (incluye los datos de referencia, por ejemplo
				                                     parámetros de negocio): cárgalas en los repositorios en memoria
				  Valores: números con punto, fechas `yyyy-MM-dd HH:mm:ss` o `yyyy-MM-dd`, `\\N` = NULL. Un parámetro
				  ausente toma su valor DEFAULT de la firma, o NULL si no tiene. Una columna ausente en una fila toma su
				  DEFAULT del esquema (DEFAULT USER → `%s`, DEFAULT SYSDATE → `%s`).

				Salida, en este orden:
				  1. Las líneas que el programa imprime con DBMS_OUTPUT.PUT_LINE, idénticas y en el mismo orden.
				  2. Si la rutina termina bien: `RET|<valor>` si es función y una línea `OUT|<PARAMETRO>|<valor>` por cada
				     parámetro de salida, en el orden de la firma.
				     Si termina con una excepción no controlada: UNA sola línea `ERR|<SQLCODE>|<mensaje>` y ninguna
				     línea RET/OUT. Ejemplos: RAISE_APPLICATION_ERROR(-20010, 'X') → `ERR|-20010|X`; NO_DATA_FOUND →
				     `ERR|100|no data found`.
				  3. Siempre (también tras ERR): el contenido final de cada tabla modificada, una línea por fila:
				     `ROW|<TABLA>|<COL>=<valor>|...` con TODAS las columnas en este orden: %s.
				     El orden entre filas no importa. Los cambios hechos antes de una excepción permanecen, salvo los que
				     el propio programa deshace (ROLLBACK TO SAVEPOINT); lo escrito en transacciones autónomas permanece.

				Reproducibilidad (el Golden Master se normalizó así; tu salida debe hacer lo mismo):
				  - Secuencias: cada ejecución empieza desde su valor inicial: %s.
				  - El usuario de base de datos (USER) se escribe como `%s`.
				  - La fecha-hora actual del sistema (SYSDATE) se escribe como `%s` y la fecha de hoy sin hora
				    (TRUNC(SYSDATE)) como `%s`. Modela el reloj como un puerto de salida y en el adaptador CLI usa un
				    reloj cuyos valores se impriman con esos marcadores.
				Formato: números con `stripTrailingZeros().toPlainString()` (0 → 0, 10661.10 → 10661.1), fechas
				`yyyy-MM-dd HH:mm:ss`, NULL → `\\N`.
				""".formatted(DeveloperAgent.mainClass(job.getArchitecture().basePackage()), routinesText(model),
				PlsqlHarness.DB_USER, PlsqlHarness.SYSTEM_TIME, written.isEmpty() ? "(ninguna)" : written, sequences,
				PlsqlHarness.DB_USER, PlsqlHarness.SYSTEM_TIME, PlsqlHarness.SYSTEM_DAY);
	}

	private static String routinesText(PlsqlModel model) {
		return model.routines()
			.stream()
			.map(r -> "- " + r.shortName() + "(" + r.params()
				.stream()
				.map(p -> p.name() + " " + p.mode() + " " + p.type())
				.collect(Collectors.joining(", ")) + ")" + (r.isFunction() ? " RETURN " + r.returnType() : ""))
			.collect(Collectors.joining("\n"));
	}

	@Override
	public String sampleRunCommand(MigrationJob job) {
		String first = job.getTestCases().stream()
			.filter(c -> "NOMINAL".equals(c.kind()))
			.map(TestCase::id)
			.findFirst()
			.orElse("TC01");
		return "docker build -t migracion . && docker run -i --rm migracion < reports/golden-master/" + first + ".in";
	}

	// ---------------------------------------------------------- utilidades

	/** DDL ejecutable: el del artefacto original más el inferido por la IA, si lo hubo. */
	private static String executableDdl(PlsqlStaticAnalyzer.Source source, MigrationJob job) {
		String inferred = job.getInferredDdl() == null ? "" : job.getInferredDdl();
		return (source.schemaDdl().strip() + "\n\n" + inferred).strip();
	}

	private static String encode(PlsqlModel model, QaAgent.CaseDraft draft) {
		StringBuilder sb = new StringBuilder();
		PlsqlModel.Routine routine = model.routine(draft.routine()).orElse(model.defaultRoutine());
		if (draft.routine() != null && !draft.routine().isBlank()) {
			sb.append("CALL|").append(draft.routine().strip().toUpperCase()).append('\n');
		}
		Map<String, String> params = draft.params() == null ? Map.of() : draft.params();
		for (PlsqlModel.Param p : routine.params()) {
			params.entrySet()
				.stream()
				.filter(e -> e.getKey().equalsIgnoreCase(p.name()))
				.findFirst()
				.ifPresent(e -> sb.append("PARAM|").append(p.name()).append('|').append(value(e.getValue())).append('\n'));
		}
		params.keySet()
			.stream()
			.filter(k -> routine.params().stream().noneMatch(p -> p.name().equalsIgnoreCase(k)))
			.forEach(k -> sb.append("PARAM|").append(k.toUpperCase()).append('|').append(value(params.get(k))).append('\n'));
		if (draft.fixtures() != null) {
			draft.fixtures().forEach((table, rows) -> {
				for (Map<String, String> row : rows == null ? List.<Map<String, String>>of() : rows) {
					sb.append("ROW|").append(table.toUpperCase());
					row.forEach((column, v) -> sb.append('|').append(column.toUpperCase()).append('=').append(value(v)));
					sb.append('\n');
				}
			});
		}
		return sb.toString().strip();
	}

	private static String value(String v) {
		return v == null ? PlsqlHarness.NULL : v.replace("|", "/").replace("\n", " ");
	}

	/**
	 * Añade al caso los datos de referencia del artefacto para las tablas que el caso no define, de modo
	 * que Oracle y el código moderno reciben exactamente la misma entrada.
	 */
	static String withSeeds(PlsqlModel model, String input) {
		if (model.seedRows().isEmpty()) {
			return input;
		}
		Set<String> tablesInCase = new LinkedHashSet<>();
		for (String line : input.split("\r?\n")) {
			String[] parts = line.strip().split("\\|", -1);
			if (parts.length > 1 && parts[0].equals("ROW")) {
				tablesInCase.add(parts[1].toUpperCase());
			}
		}
		StringBuilder seeds = new StringBuilder();
		for (PlsqlModel.SeedRow row : model.seedRows()) {
			if (tablesInCase.contains(row.table())) {
				continue;
			}
			seeds.append("ROW|").append(row.table());
			row.values().forEach((column, v) -> seeds.append('|').append(column).append('=').append(value(v)));
			seeds.append('\n');
		}
		if (seeds.isEmpty()) {
			return input;
		}
		// CALL y PARAM primero, después los datos de referencia y por último las filas del caso.
		StringBuilder head = new StringBuilder();
		StringBuilder rows = new StringBuilder();
		for (String line : input.split("\r?\n")) {
			if (line.startsWith("ROW|")) {
				rows.append(line).append('\n');
			}
			else if (!line.isBlank()) {
				head.append(line).append('\n');
			}
		}
		return (head.toString() + seeds + rows).strip();
	}

	private static void addIfValid(MigrationJob job, PlsqlModel model, List<TestCase> cases, Set<String> seen, String input,
			String description, String kind, List<String> ruleIds) {
		if (input == null || input.isBlank()) {
			return;
		}
		input = withSeeds(model, input.strip());
		if (!seen.add(input)) {
			return;
		}
		String problem;
		try {
			problem = PlsqlHarness.validate(model, PlsqlHarness.parse(input));
		}
		catch (IllegalArgumentException ex) {
			problem = ex.getMessage();
		}
		if (problem != null) {
			job.log(Phase.GENERACION, "WARN", "Caso descartado (" + description + "): " + problem);
			return;
		}
		cases.add(new TestCase("TC%02d".formatted(cases.size() + 1), description, kind, input, ruleIds));
	}

	private static String signature(PlsqlModel model) {
		return model.entryPoint() + "(" + model.params()
			.stream()
			.map(p -> p.name() + " " + p.mode() + " " + p.type())
			.collect(Collectors.joining(", ")) + ")" + (model.isFunction() ? " RETURN " + model.returnType() : "");
	}

	private static String schemaText(PlsqlModel model) {
		return model.schema()
			.stream()
			.map(t -> "- " + t.name() + "(" + t.columns()
				.stream()
				.map(c -> c.name() + " " + c.type() + (c.nullable() ? "" : " NOT NULL"))
				.collect(Collectors.joining(", ")) + ")")
			.collect(Collectors.joining("\n"));
	}

}
