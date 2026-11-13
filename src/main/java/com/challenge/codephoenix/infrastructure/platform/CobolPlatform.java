package com.challenge.codephoenix.infrastructure.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.application.GateFailedException;
import com.challenge.codephoenix.application.LegacyPlatform;
import com.challenge.codephoenix.application.RecordEncoder;
import com.challenge.codephoenix.domain.MigrationJob;
import com.challenge.codephoenix.domain.Phase;
import com.challenge.codephoenix.domain.StaticAnalysis;
import com.challenge.codephoenix.domain.TestCase;
import com.challenge.codephoenix.infrastructure.ai.DeveloperAgent;
import com.challenge.codephoenix.infrastructure.ai.QaAgent;
import com.challenge.codephoenix.infrastructure.analysis.CobolStaticAnalyzer;
import com.challenge.codephoenix.infrastructure.analysis.LanguageDetector;
import com.challenge.codephoenix.infrastructure.sandbox.ExecutionService;

/** COBOL: se compila y ejecuta con GnuCOBOL; los casos son registros de ancho fijo leídos con ACCEPT. */
@Component
public class CobolPlatform implements LegacyPlatform {

	private final CobolStaticAnalyzer analyzer;
	private final ExecutionService execution;

	public CobolPlatform(CobolStaticAnalyzer analyzer, ExecutionService execution) {
		this.analyzer = analyzer;
		this.execution = execution;
	}

	@Override
	public String language() {
		return LanguageDetector.COBOL;
	}

	@Override
	public String runtimeDescription() {
		return "GnuCOBOL en un contenedor sin red";
	}

	@Override
	public String unitTerm() {
		return "párrafo COBOL";
	}

	@Override
	public String verifyArtifact(MigrationJob job, Path jobDir) throws IOException, InterruptedException {
		ExecutionService.RunOutcome syntax = execution.checkLegacy(jobDir, job.getSourceName());
		if (!syntax.ok()) {
			throw new GateFailedException(Phase.INGESTA,
					"El artefacto no compila en GnuCOBOL (¿falta un copybook o está incompleto?):\n" + tail(syntax.log()));
		}
		return "COBOL identificado y compilable en GnuCOBOL";
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
	public void prepareExecution(MigrationJob job, Path jobDir) {
		// El programa ya se compiló en la fase 1; no necesita datos externos.
	}

	@Override
	public String caseInstructions(MigrationJob job) {
		String layout = job.getAnalysis()
			.inputRecord()
			.stream()
			.map(f -> "- %s: posiciones %d-%d, %d dígitos, %d decimales implícitos".formatted(f.name(), f.start(),
					f.start() + f.length() - 1, f.length(), f.decimals()))
			.collect(Collectors.joining("\n"));
		return """
				Diseño del registro de entrada (derivado del análisis estático):
				%s
				- Para registros con formato válido llena `fields` con TODOS los campos del diseño (clave = nombre del
				  campo, valor = número decimal en texto con punto, por ejemplo "100000.00", "18.5", "360") y deja
				  `input` vacío: CodePhoenix codifica el registro de ancho fijo.
				- Para entradas mal formadas (letras, registro incompleto) deja `fields` vacío y escribe `input` literal.
				- Incluye al menos 2 entradas mal formadas.
				- Deja `params` y `fixtures` vacíos.
				- Para no generar salidas enormes, usa plazos cortos salvo un caso con el valor máximo permitido.
				""".formatted(layout);
	}

	@Override
	public List<TestCase> toTestCases(MigrationJob job, List<QaAgent.CaseDraft> drafts) {
		List<TestCase> cases = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		List<StaticAnalysis.RecordField> layout = job.getAnalysis().inputRecord();
		for (QaAgent.CaseDraft draft : drafts) {
			String input;
			if (draft.fields() != null && !draft.fields().isEmpty()) {
				input = RecordEncoder.encode(layout, draft.fields()).orElse(null);
				if (input == null) {
					job.log(Phase.GENERACION, "WARN", "Caso descartado: los valores no caben en el registro " + draft.fields());
					continue;
				}
			}
			else {
				input = draft.input();
			}
			if (input != null && seen.add(input)) {
				cases.add(new TestCase("TC%02d".formatted(cases.size() + 1), draft.description(),
						draft.kind() == null ? "NOMINAL" : draft.kind(), input,
						draft.ruleIds() == null ? List.of() : draft.ruleIds()));
			}
		}
		for (String line : String.join("\n", job.getCaseInputs()).split("\r?\n")) {
			if (!line.isBlank() && seen.add(line.strip())) {
				cases.add(new TestCase("TC%02d".formatted(cases.size() + 1), "Caso aportado por el usuario", "USUARIO",
						line.strip(), List.of()));
			}
		}
		return cases;
	}

	@Override
	public String describeInput(MigrationJob job, String input) {
		return describeRecord(job.getAnalysis().inputRecord(), input);
	}

	/** Decodifica un registro de ancho fijo según su diseño: "IN-MONTO=25000.50 · IN-PLAZO=10 ...". */
	static String describeRecord(List<StaticAnalysis.RecordField> layout, String input) {
		if (layout.isEmpty() || input == null) {
			return "";
		}
		List<String> parts = new ArrayList<>();
		for (StaticAnalysis.RecordField field : layout) {
			int start = field.start() - 1;
			String raw = start >= input.length() ? "" : input.substring(start, Math.min(input.length(), start + field.length()));
			if (raw.length() < field.length() || !raw.chars().allMatch(Character::isDigit)) {
				parts.add(field.name() + "=«" + raw + "» (no numérico o incompleto)");
				continue;
			}
			String digits = raw.replaceFirst("^0+(?=\\d)", "");
			String value = field.decimals() == 0 ? digits
					: new java.math.BigDecimal(new java.math.BigInteger(raw), field.decimals()).toPlainString();
			parts.add(field.name() + "=" + value);
		}
		return String.join(" · ", parts);
	}

	@Override
	public ExecutionService.RunOutcome runLegacy(MigrationJob job, Path jobDir, List<TestCase> cases)
			throws IOException, InterruptedException {
		return execution.runLegacy(jobDir, job.getSourceName(), cases);
	}

	@Override
	public String architectureHint(MigrationJob job) {
		return """
				- Paquetes: <basePackage>.domain.model, <basePackage>.domain.service, <basePackage>.application.port.in,
				  <basePackage>.application.service, <basePackage>.adapter.in.cli.
				- Existirá un adaptador CLI que reproduce el contrato de E/S del legacy (registro de entrada de ancho
				  fijo y salida idéntica) para demostrar la equivalencia funcional, y un contrato REST (OpenAPI) para la
				  coexistencia con el sistema legado mediante el patrón Strangler Fig.
				""";
	}

	@Override
	public String ioContract(MigrationJob job) {
		return """
				Adaptador CLI `%s` con:
				- `public static java.util.List<String> process(String record)`: recibe UNA línea de entrada tal
				  cual la leería el legacy y devuelve las líneas de salida idénticas al legacy (incluidos errores).
				- `public static void main(String[] args)`: lee una línea de System.in (vacía si no hay), llama a
				  process e imprime cada línea con System.out.println.
				""".formatted(DeveloperAgent.mainClass(job.getArchitecture().basePackage()));
	}

	@Override
	public String sampleRunCommand(MigrationJob job) {
		String record = job.getTestCases().stream()
			.filter(c -> "NOMINAL".equals(c.kind()))
			.map(TestCase::input)
			.findFirst()
			.orElse("<registro>");
		return "docker build -t migracion . && echo \"" + record + "\" | docker run -i --rm migracion";
	}

	private static String tail(String text) {
		return text.length() <= 1500 ? text : "…" + text.substring(text.length() - 1500);
	}

}
