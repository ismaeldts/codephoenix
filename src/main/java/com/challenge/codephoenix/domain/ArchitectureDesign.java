package com.challenge.codephoenix.domain;

import java.util.List;

public record ArchitectureDesign(String basePackage, String summary, List<DomainElement> elements, List<Adr> adrs,
		String openApiYaml, String mermaidDiagram) {

	public record DomainElement(String name, String kind, String layer, String responsibility) {
	}

	public record Adr(String id, String title, String context, String decision, String consequences) {
	}

}
