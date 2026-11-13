package com.challenge.codephoenix.infrastructure.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Capa de conocimiento: estándares, mapeos de tipos legacy y glosario del
 * dominio. Se recupera por tema y se inyecta en el contexto de los agentes.
 */
@Component
public class KnowledgeBase {

	public enum Topic { COBOL_JAVA_MAPPING, PLSQL_JAVA_MAPPING, CODING_STANDARDS, BANKING_GLOSSARY }

	private static final Map<Topic, String> FILES = Map.of(Topic.COBOL_JAVA_MAPPING, "knowledge/cobol-java-mapping.md",
			Topic.PLSQL_JAVA_MAPPING, "knowledge/plsql-java-mapping.md", Topic.CODING_STANDARDS,
			"knowledge/coding-standards.md", Topic.BANKING_GLOSSARY, "knowledge/banking-glossary.md");

	private final Map<Topic, String> documents = new LinkedHashMap<>();

	public KnowledgeBase() {
		FILES.forEach((topic, path) -> {
			try {
				documents.put(topic, new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		});
	}

	public String retrieve(List<Topic> topics) {
		return topics.stream().map(documents::get).collect(Collectors.joining("\n\n"));
	}

	public List<Topic> topicsFor(String language) {
		return switch (language == null ? "" : language) {
			case "COBOL" -> List.of(Topic.COBOL_JAVA_MAPPING, Topic.CODING_STANDARDS, Topic.BANKING_GLOSSARY);
			case "PL/SQL" -> List.of(Topic.PLSQL_JAVA_MAPPING, Topic.CODING_STANDARDS, Topic.BANKING_GLOSSARY);
			default -> List.of(Topic.CODING_STANDARDS, Topic.BANKING_GLOSSARY);
		};
	}

}
