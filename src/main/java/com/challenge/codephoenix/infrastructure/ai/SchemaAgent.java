package com.challenge.codephoenix.infrastructure.ai;

import java.util.List;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.domain.MigrationJob;

/**
 * Cuando el artefacto PL/SQL usa tablas cuyo DDL no se entregó, deduce un
 * esquema mínimo a partir del uso que hace el código. El resultado se marca
 * como inferido para que el ingeniero lo revise.
 */
@Component
public class SchemaAgent {

	public record InferredSchema(String ddl, List<String> assumptions) {
	}

	private static final String SYSTEM = """
			Eres el Agente Analista de datos de un copiloto de modernización de código legacy.
			Deduces el DDL de Oracle mínimo y compilable de las tablas que usa un programa PL/SQL a partir de cómo
			las usa (columnas leídas y escritas, comparaciones, tipos de las variables donde se cargan). Respondes
			en español.
			""";

	private final AgentGateway gateway;

	public SchemaAgent(AgentGateway gateway) {
		this.gateway = gateway;
	}

	public InferredSchema infer(MigrationJob job, List<String> missingTables) {
		String user = """
				Código PL/SQL (con número de línea):
				%s

				Faltan las definiciones de estas tablas: %s

				Escribe en `ddl` una sentencia CREATE TABLE por tabla (terminadas en ';', sin esquema propietario),
				con TODAS las columnas que el código usa, tipos Oracle coherentes con su uso (NUMBER(p,s) para importes,
				DATE para fechas, VARCHAR2(n) para textos), clave primaria cuando se deduzca y NOT NULL solo si es
				evidente. En `assumptions` explica brevemente cada decisión dudosa.
				""".formatted(PromptSupport.numbered(job.getMaskedCode()), String.join(", ", missingTables));
		return gateway.structured(job, "Analista de datos (esquema)", AgentGateway.Profile.ESTANDAR, SYSTEM, user,
				InferredSchema.class);
	}

}
