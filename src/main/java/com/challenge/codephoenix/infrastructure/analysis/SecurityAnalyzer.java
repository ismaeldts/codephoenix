package com.challenge.codephoenix.infrastructure.analysis;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.challenge.codephoenix.domain.QualityReport.Finding;
import com.challenge.codephoenix.domain.SecurityReport;

/**
 * Interpreta el reporte XML de SpotBugs + FindSecBugs (SAST sobre el bytecode del código
 * generado) y revisa las dependencias de producción.
 *
 * <p>Clasificación: vulnerabilidades (categoría SECURITY) de prioridad alta o media y errores de
 * corrección de prioridad alta son CRITICOS y bloquean el quality gate; el resto se informa.
 */
@Component
public class SecurityAnalyzer {

	public static final String TOOL = "SpotBugs 4.8.6 + FindSecBugs 1.13.0";

	/** Descripciones de los patrones más frecuentes (el XML de SpotBugs no siempre incluye el mensaje). */
	private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
			Map.entry("DM_CONVERT_CASE", "Conversión a mayúsculas/minúsculas sin Locale explícito"),
			Map.entry("IMPROPER_UNICODE", "Transformación o comparación de texto Unicode que puede eludir validaciones"),
			Map.entry("EI_EXPOSE_REP", "Devuelve una referencia a un objeto mutable interno"),
			Map.entry("EI_EXPOSE_REP2", "Guarda una referencia a un objeto mutable recibido"),
			Map.entry("PREDICTABLE_RANDOM", "Generador de números aleatorios predecible"),
			Map.entry("PATH_TRAVERSAL_IN", "Ruta de archivo construida con datos de entrada (path traversal)"),
			Map.entry("COMMAND_INJECTION", "Comando del sistema construido con datos de entrada"),
			Map.entry("SQL_INJECTION_JDBC", "Consulta SQL construida concatenando datos de entrada"),
			Map.entry("CRLF_INJECTION_LOGS", "Datos de entrada escritos en el log sin neutralizar saltos de línea"),
			Map.entry("NP_NULL_ON_SOME_PATH", "Posible NullPointerException en algún camino"),
			Map.entry("DM_DEFAULT_ENCODING", "Uso de la codificación por defecto de la plataforma"),
			Map.entry("UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", "Campo no inicializado en el constructor"));

	private static final Pattern IMPORT =Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);

	public SecurityReport fromSpotBugs(String xml, Map<String, String> files, String basePackage, String output) {
		List<Finding> findings = new ArrayList<>();
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
			NodeList bugs = doc.getElementsByTagName("BugInstance");
			for (int i = 0; i < bugs.getLength(); i++) {
				Element bug = (Element) bugs.item(i);
				String category = bug.getAttribute("category");
				int priority = Integer.parseInt(bug.getAttribute("priority"));
				String type = bug.getAttribute("type");
				String message = text(bug, "LongMessage");
				if (message.isEmpty()) {
					NodeList methods = bug.getElementsByTagName("Method");
					String method = methods.getLength() == 0 ? "" : ((Element) methods.item(0)).getAttribute("name");
					message = DESCRIPTIONS.getOrDefault(type, "Patrón " + type + " detectado por " + TOOL)
							+ (method.isEmpty() ? "" : " (método " + method + ")");
				}
				String file = "-";
				int line = 0;
				NodeList sourceLines = bug.getElementsByTagName("SourceLine");
				for (int k = 0; k < sourceLines.getLength(); k++) {
					Element sl = (Element) sourceLines.item(k);
					if (sl.getParentNode() == bug || k == sourceLines.getLength() - 1) {
						file = "src/main/java/" + sl.getAttribute("sourcepath");
						String start = sl.getAttribute("start");
						line = start.isEmpty() ? 0 : Integer.parseInt(start);
						break;
					}
				}
				findings.add(new Finding(severity(category, priority), "SAST:" + type + " (" + category + ")", file, line,
						message));
			}
		}
		catch (Exception ex) {
			return new SecurityReport(TOOL, false, 0, 0, 0, List.of(), productionDependencies(files, basePackage),
					"No se pudo leer el reporte de SpotBugs: " + ex.getMessage() + "\n" + output);
		}
		int critical = count(findings, "CRITICO");
		int major = count(findings, "MAYOR");
		int minor = findings.size() - critical - major;
		return new SecurityReport(TOOL, true, critical, major, minor, findings, productionDependencies(files, basePackage),
				output);
	}

	/** Paquetes externos (fuera del JDK y del propio proyecto) que importa el código de producción. */
	public List<String> productionDependencies(Map<String, String> files, String basePackage) {
		Set<String> external = new TreeSet<>();
		files.forEach((path, content) -> {
			if (!path.startsWith("src/main/java/")) {
				return;
			}
			Matcher m = IMPORT.matcher(content);
			while (m.find()) {
				String imported = m.group(1);
				boolean jdk = imported.startsWith("java.") || imported.startsWith("javax.")
						|| imported.startsWith("com.sun.net.httpserver") || imported.startsWith("jdk.");
				if (!jdk && (basePackage == null || !imported.startsWith(basePackage))) {
					external.add(imported.contains(".") ? imported.substring(0, imported.lastIndexOf('.')) : imported);
				}
			}
		});
		return List.copyOf(external);
	}

	static String severity(String category, int priority) {
		if ("SECURITY".equals(category)) {
			return priority <= 2 ? "CRITICO" : "MAYOR";
		}
		if ("CORRECTNESS".equals(category) || "MT_CORRECTNESS".equals(category)) {
			return priority == 1 ? "CRITICO" : "MAYOR";
		}
		return "MENOR";
	}

	private static int count(List<Finding> findings, String severity) {
		return (int) findings.stream().filter(f -> f.severity().equals(severity)).count();
	}

	private static String text(Element parent, String tag) {
		NodeList nodes = parent.getElementsByTagName(tag);
		return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
	}

}
