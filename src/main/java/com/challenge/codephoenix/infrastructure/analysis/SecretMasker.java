package com.challenge.codephoenix.infrastructure.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.MaskingFinding;

/**
 * Detecta y enmascara secretos y datos personales antes de que el código salga
 * hacia el modelo de IA. Conserva el número de líneas para no romper la
 * trazabilidad de las reglas.
 */
@Component
public class SecretMasker {

	public record Result(String maskedCode, List<MaskingFinding> findings) {
	}

	/** Grupo que contiene el valor a enmascarar; FIRST_GROUP = el primer grupo que participó en la coincidencia. */
	private record Detector(String type, Pattern pattern, int group) {
	}

	private static final int FIRST_GROUP = -1;

	private static int firstMatchedGroup(Matcher matcher) {
		for (int i = 1; i <= matcher.groupCount(); i++) {
			if (matcher.group(i) != null) {
				return i;
			}
		}
		return 0;
	}

	private static final List<Detector> DETECTORS = List.of(
			new Detector("CADENA_CONEXION",
					Pattern.compile("(?i)\\b(?:jdbc:[a-z0-9]+:|db2://|oracle://|mysql://|postgres(?:ql)?://|mongodb(?:\\+srv)?://|sqlserver://)[^\\s'\"]+"), 0),
			new Detector("CLAVE_API",
					Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|xox[bp]-[A-Za-z0-9-]{20,})"), 0),
			// Asignación de configuración (clave=valor, clave: valor) o asignación PL/SQL de un literal (:= '...').
			// Una asignación PL/SQL de una variable (v_usuario := p_usuario) no es un secreto.
			new Detector("CONTRASENA",
					Pattern.compile("(?i)(?<![A-Za-z0-9])(?:password|passwd|pwd|pass|clave|contrasena|secret|token|api[_-]?key)"
							+ "(?:\\s*(?:=|:(?!=))\\s*['\"]?([^\\s'\";*]+)|\\s*:=\\s*'([^']+)')"), FIRST_GROUP),
			new Detector("USUARIO",
					Pattern.compile("(?i)(?<![A-Za-z0-9])(?:user|usuario|userid|uid|usr)"
							+ "(?:\\s*(?:=|:(?!=))\\s*['\"]?([^\\s'\";*]+)|\\s*:=\\s*'([^']+)')"), FIRST_GROUP),
			new Detector("EMAIL",
					Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), 0),
			new Detector("DIRECCION_IP",
					Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b"), 0),
			new Detector("TARJETA",
					Pattern.compile("\\b\\d{13,19}\\b"), 0));

	public Result mask(String code) {
		String[] lines = code.split("\r?\n", -1);
		List<MaskingFinding> findings = new ArrayList<>();
		Map<String, Integer> counters = new HashMap<>();
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			for (Detector detector : DETECTORS) {
				line = apply(detector, line, i + 1, findings, counters);
			}
			lines[i] = line;
		}
		return new Result(String.join("\n", lines), findings);
	}

	private String apply(Detector detector, String line, int lineNumber, List<MaskingFinding> findings,
			Map<String, Integer> counters) {
		Matcher matcher = detector.pattern().matcher(line);
		StringBuilder out = new StringBuilder();
		int last = 0;
		while (matcher.find()) {
			int group = detector.group() == FIRST_GROUP ? firstMatchedGroup(matcher) : detector.group();
			String value = matcher.group(group);
			if (value.startsWith("[") || ("TARJETA".equals(detector.type()) && !passesLuhn(value))) {
				continue;
			}
			int n = counters.merge(detector.type(), 1, Integer::sum);
			String token = "[" + detector.type() + "_" + n + "]";
			out.append(line, last, matcher.start(group)).append(token);
			last = matcher.end(group);
			findings.add(new MaskingFinding(detector.type(), lineNumber, token));
		}
		out.append(line.substring(last));
		return out.toString();
	}

	private static boolean passesLuhn(String digits) {
		int sum = 0;
		boolean doubleIt = false;
		for (int i = digits.length() - 1; i >= 0; i--) {
			int d = digits.charAt(i) - '0';
			if (doubleIt) {
				d *= 2;
				if (d > 9) {
					d -= 9;
				}
			}
			sum += d;
			doubleIt = !doubleIt;
		}
		return sum % 10 == 0;
	}

}
