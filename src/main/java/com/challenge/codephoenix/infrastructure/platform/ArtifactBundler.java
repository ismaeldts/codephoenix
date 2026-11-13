package com.challenge.codephoenix.infrastructure.platform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.application.InvalidRequestException;
import com.challenge.codephoenix.domain.PlsqlModel;
import com.challenge.codephoenix.infrastructure.analysis.PlsqlStaticAnalyzer;
import com.challenge.codephoenix.infrastructure.analysis.SqlInsertParser;

/**
 * Prepara un artefacto a partir de un .zip o de varios archivos: identifica el papel de cada uno
 * (esquema, datos de referencia, especificación, cuerpo, datos de prueba, script de ejecución), los
 * une en el orden en que deben ejecutarse y convierte los datos de prueba del proyecto en casos de
 * caracterización. No ejecuta nada: solo lee texto.
 */
@Component
public class ArtifactBundler {

	public record InputFile(String name, byte[] content) {
	}

	public record BundleFile(String name, String role, boolean included) {
	}

	public record Bundle(String sourceName, String code, List<BundleFile> files, List<String> caseInputs,
			List<String> notes) {
	}

	enum Role {

		ESQUEMA("Esquema (tablas, secuencias)", 1, true),
		DATOS_REFERENCIA("Datos de referencia", 2, true),
		ESPECIFICACION("Especificación del paquete", 3, true),
		PROGRAMA("Programa (cuerpo o rutina)", 4, true),
		COBOL("Programa COBOL", 4, true),
		DATOS_PRUEBA("Datos de prueba → caso", 9, false),
		EJECUCION("Script de ejecución de prueba → caso", 9, false),
		OTRO("No reconocido (se ignora)", 9, false);

		final String label;
		final int order;
		final boolean partOfArtifact;

		Role(String label, int order, boolean partOfArtifact) {
			this.label = label;
			this.order = order;
			this.partOfArtifact = partOfArtifact;
		}

	}

	private static final Set<String> EXTENSIONS = Set.of("sql", "pks", "pkb", "pls", "plsql", "prc", "fnc", "trg",
			"ddl", "dml", "cbl", "cob", "cpy", "txt");
	private static final int MAX_FILES = 200;
	private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
	private static final Pattern TEST_PATH = Pattern.compile("(?i)(prueba|test|fixture|ejemplo|sample|datos_)");

	private final PlsqlStaticAnalyzer plsqlAnalyzer;

	public ArtifactBundler(PlsqlStaticAnalyzer plsqlAnalyzer) {
		this.plsqlAnalyzer = plsqlAnalyzer;
	}

	public Bundle bundle(List<InputFile> uploaded) throws IOException {
		Map<String, String> texts = new LinkedHashMap<>();
		String zipName = null;
		for (InputFile file : uploaded) {
			if (file.name().toLowerCase(Locale.ROOT).endsWith(".zip")) {
				zipName = baseName(file.name()).replaceFirst("(?i)\\.zip$", "");
				texts.putAll(unzip(file.content()));
			}
			else if (accepted(file.name())) {
				texts.put(file.name(), new String(file.content(), StandardCharsets.UTF_8));
			}
		}
		if (texts.isEmpty()) {
			throw new InvalidRequestException("No hay archivos de código reconocibles (.sql, .pks, .pkb, .cbl, ...).");
		}

		Map<String, Role> roles = new LinkedHashMap<>();
		texts.forEach((name, text) -> roles.put(name, classify(name, text)));
		List<String> ordered = texts.keySet()
			.stream()
			.sorted(Comparator.comparingInt((String n) -> roles.get(n).order).thenComparing(ArtifactBundler::baseName))
			.toList();

		List<String> notes = new ArrayList<>();
		boolean cobol = roles.containsValue(Role.COBOL);
		List<String> included = new ArrayList<>();
		if (cobol) {
			String program = ordered.stream().filter(n -> roles.get(n) == Role.COBOL).findFirst().orElseThrow();
			included.add(program);
			ordered.stream()
				.filter(n -> !n.equals(program) && roles.get(n).partOfArtifact)
				.forEach(n -> notes.add("Se ignora " + baseName(n) + ": esta versión migra un programa COBOL por vez "
						+ "(sin copybooks)."));
		}
		else {
			ordered.stream().filter(n -> roles.get(n).partOfArtifact).forEach(included::add);
		}
		String code = included.stream().map(texts::get).map(String::strip).collect(Collectors.joining("\n\n")) + "\n";

		List<BundleFile> files = ordered.stream()
			.map(n -> new BundleFile(n, roles.get(n).label, included.contains(n)))
			.toList();
		String sourceName = zipName != null ? zipName + (cobol ? ".cbl" : ".sql") : baseName(included.getFirst());

		List<String> caseInputs = cobol ? List.of() : testCases(code, texts, roles, notes);
		return new Bundle(sourceName, code, files, caseInputs, notes);
	}

	/** Convierte los datos de prueba y los scripts de ejecución del proyecto en casos PARAM/ROW/CALL. */
	private List<String> testCases(String code, Map<String, String> texts, Map<String, Role> roles, List<String> notes) {
		String testData = texts.entrySet()
			.stream()
			.filter(e -> roles.get(e.getKey()) == Role.DATOS_PRUEBA)
			.map(Map.Entry::getValue)
			.collect(Collectors.joining("\n"));
		List<SqlInsertParser.Call> calls = texts.entrySet()
			.stream()
			.filter(e -> roles.get(e.getKey()) == Role.EJECUCION)
			.flatMap(e -> SqlInsertParser.parseCalls(e.getValue()).stream())
			.toList();
		if (testData.isBlank() && calls.isEmpty()) {
			return List.of();
		}
		PlsqlModel model = plsqlAnalyzer.analyze(code).plsql();
		SqlInsertParser.Result rows = SqlInsertParser.parseInserts(testData, model.schema());
		rows.skipped().stream().limit(5).forEach(s -> notes.add("Fila de prueba no convertida: " + s));
		if (calls.isEmpty()) {
			notes.add("Los datos de prueba no tienen un script de ejecución (BEGIN ... END) que indique qué rutina "
					+ "invocar; añade uno o define los casos a mano.");
			return List.of();
		}
		List<String> cases = new ArrayList<>();
		for (SqlInsertParser.Call call : calls) {
			PlsqlModel.Routine routine = model.routine(call.routine()).orElse(null);
			if (routine == null) {
				notes.add("El script de prueba invoca " + call.routine() + ", que no es una rutina pública del artefacto.");
				continue;
			}
			StringBuilder sb = new StringBuilder("CALL|").append(routine.shortName()).append('\n');
			call.arguments().forEach((key, value) -> {
				String name = key.startsWith("#") ? positional(routine, Integer.parseInt(key.substring(1))) : key;
				if (name != null) {
					sb.append("PARAM|").append(name).append('|').append(value).append('\n');
				}
			});
			for (PlsqlModel.SeedRow row : rows.rows()) {
				sb.append("ROW|").append(row.table());
				row.values().forEach((column, v) -> sb.append('|').append(column).append('=')
					.append(v.replace("|", "/").replace("\n", " ")));
				sb.append('\n');
			}
			cases.add(sb.toString().strip());
			notes.add("Caso del proyecto: " + routine.shortName() + " con " + rows.rows().size() + " filas de prueba.");
		}
		return cases;
	}

	private static String positional(PlsqlModel.Routine routine, int index) {
		return index >= 1 && index <= routine.params().size() ? routine.params().get(index - 1).name() : null;
	}

	static Role classify(String name, String text) {
		String lower = name.toLowerCase(Locale.ROOT);
		String sql = SqlInsertParser.stripComments(text).toUpperCase(Locale.ROOT);
		if (lower.endsWith(".cpy")) {
			return Role.OTRO;
		}
		if (sql.matches("(?s).*\\b(IDENTIFICATION|PROCEDURE)\\s+DIVISION\\b.*")) {
			return Role.COBOL;
		}
		if (sql.matches("(?s).*CREATE\\s+(OR\\s+REPLACE\\s+)?((NON)?EDITIONABLE\\s+)?PACKAGE\\s+BODY\\b.*")
				|| sql.matches("(?s).*CREATE\\s+(OR\\s+REPLACE\\s+)?((NON)?EDITIONABLE\\s+)?(PROCEDURE|FUNCTION|TRIGGER)\\b.*")) {
			return Role.PROGRAMA;
		}
		if (sql.matches("(?s).*CREATE\\s+(OR\\s+REPLACE\\s+)?((NON)?EDITIONABLE\\s+)?PACKAGE\\b.*")) {
			return Role.ESPECIFICACION;
		}
		if (sql.matches("(?s).*CREATE\\s+(GLOBAL\\s+TEMPORARY\\s+)?(TABLE|SEQUENCE|INDEX|VIEW|UNIQUE\\s+INDEX)\\b.*")) {
			return Role.ESQUEMA;
		}
		boolean inserts = sql.matches("(?s).*\\b(INSERT\\s+INTO|MERGE\\s+INTO)\\b.*");
		boolean isTestPath = TEST_PATH.matcher(lower).find();
		if (inserts) {
			return isTestPath && !lower.contains("param") ? Role.DATOS_PRUEBA : Role.DATOS_REFERENCIA;
		}
		if (sql.matches("(?s).*\\bBEGIN\\b.*\\bEND\\s*;.*")) {
			return Role.EJECUCION;
		}
		return Role.OTRO;
	}

	private static Map<String, String> unzip(byte[] zip) throws IOException {
		Map<String, String> texts = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			int count = 0;
			while ((entry = in.getNextEntry()) != null) {
				String name = entry.getName().replace('\\', '/');
				if (entry.isDirectory() || name.startsWith("__MACOSX/") || baseName(name).startsWith("._")
						|| name.contains("..") || !accepted(name)) {
					continue;
				}
				if (++count > MAX_FILES) {
					throw new InvalidRequestException("El .zip tiene más de " + MAX_FILES + " archivos de código.");
				}
				byte[] content = in.readNBytes(MAX_FILE_BYTES + 1);
				if (content.length > MAX_FILE_BYTES) {
					throw new InvalidRequestException("El archivo " + name + " supera 2 MB.");
				}
				texts.put(name, new String(content, StandardCharsets.UTF_8));
			}
		}
		return texts;
	}

	private static boolean accepted(String name) {
		String base = baseName(name);
		int dot = base.lastIndexOf('.');
		return dot > 0 && EXTENSIONS.contains(base.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private static String baseName(String name) {
		return name.replace('\\', '/').replaceAll(".*/", "");
	}

}
