package com.challenge.codephoenix.infrastructure.analysis;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class LanguageDetector {

	public static final String COBOL = "COBOL";

	private static final Pattern COBOL_MARKERS = Pattern
		.compile("(?im)^.{0,7}\\s*(IDENTIFICATION|PROCEDURE)\\s+DIVISION");
	private static final Pattern PLSQL_MARKERS = Pattern
		.compile("(?i)CREATE\\s+(OR\\s+REPLACE\\s+)?(PROCEDURE|PACKAGE|FUNCTION|TRIGGER)");
	private static final Pattern RPG_MARKERS = Pattern.compile("(?im)^\\s*(\\*\\*FREE|DCL-S|DCL-PROC|CTL-OPT)");
	private static final Pattern JAVA_MARKERS = Pattern.compile("(?m)^\\s*(public\\s+)?(class|interface)\\s+\\w+");

	public String detect(String code) {
		if (COBOL_MARKERS.matcher(code).find()) {
			return COBOL;
		}
		if (PLSQL_MARKERS.matcher(code).find()) {
			return "PL/SQL";
		}
		if (RPG_MARKERS.matcher(code).find()) {
			return "RPG";
		}
		if (JAVA_MARKERS.matcher(code).find()) {
			return "Java";
		}
		return "DESCONOCIDO";
	}

}
