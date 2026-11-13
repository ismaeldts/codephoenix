package com.challenge.codephoenix.infrastructure.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.anthropic.models.messages.OutputConfig;
import com.challenge.codephoenix.config.CodephoenixProperties;
import com.challenge.codephoenix.domain.AuditEntry;
import com.challenge.codephoenix.domain.MigrationJob;

/**
 * Punto único de salida hacia el modelo. Registra cada invocación en la
 * bitácora de auditoría del job (agente, hash del prompt, tokens, duración y
 * motivo de finalización) y guarda la respuesta cruda en el workspace del job
 * para poder diagnosticarla.
 */
@Component
public class AgentGateway {

	private static final Logger log = LoggerFactory.getLogger(AgentGateway.class);

	private final ChatClient chatClient;
	private final String model;
	private final Path workspace;

	public AgentGateway(ChatClient.Builder builder, CodephoenixProperties properties,
			@Value("${spring.ai.anthropic.chat.options.model:desconocido}") String model) {
		this.chatClient = builder.build();
		this.model = model;
		this.workspace = properties.workspace();
	}

	/**
	 * Presupuesto de razonamiento y de salida de cada agente. El razonamiento
	 * consume tokens de salida, así que las tareas creativas cortas usan poco
	 * esfuerzo y la generación de código usa el máximo presupuesto.
	 */
	public enum Profile {

		LIGERO(OutputConfig.Effort.LOW, 6_000),
		ESTANDAR(OutputConfig.Effort.MEDIUM, 20_000),
		CODIGO(OutputConfig.Effort.MEDIUM, 64_000),
		PROFUNDO(OutputConfig.Effort.HIGH, 64_000);

		private final OutputConfig.Effort effort;
		private final int maxTokens;

		Profile(OutputConfig.Effort effort, int maxTokens) {
			this.effort = effort;
			this.maxTokens = maxTokens;
		}

	}

	public <T> T structured(MigrationJob job, String agent, Profile profile, String system, String user,
			Class<T> type) {
		BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
		String text = text(job, agent, profile, system, user + "\n\n" + converter.getFormat());
		try {
			return converter.convert(text);
		}
		catch (RuntimeException ex) {
			throw new AgentException(agent + " devolvió una respuesta que no cumple el esquema JSON esperado: "
					+ ex.getMessage(), ex);
		}
	}

	public String text(MigrationJob job, String agent, Profile profile, String system, String user) {
		long start = System.currentTimeMillis();
		String hash = sha256(system + "\n---\n" + user);
		Reply reply;
		try {
			ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
				.options(AnthropicChatOptions.builder()
					.model(model)
					.maxTokens(profile.maxTokens)
					.effort(profile.effort))
				.system(system)
				.user(user);
			reply = profile.maxTokens > NON_STREAMING_MAX_TOKENS ? streamed(spec) : called(spec);
		}
		catch (RuntimeException ex) {
			job.recordAudit(new AuditEntry(Instant.now(), agent, model, hash, true, 0, 0,
					System.currentTimeMillis() - start, "ERROR: " + ex.getClass().getSimpleName()));
			throw new AgentException(agent + " no respondió: " + ex.getMessage(), ex);
		}
		long in = reply.inputTokens();
		long out = reply.outputTokens();
		String finish = reply.finishReason();
		String text = reply.text();
		saveRaw(job, agent, text);
		boolean truncated = finish.toLowerCase(Locale.ROOT).contains("max_tokens")
				|| finish.toLowerCase(Locale.ROOT).contains("length");
		String outcome = truncated ? "TRUNCADA (límite de tokens)" : text == null || text.isBlank() ? "VACÍA" : "OK";
		job.recordAudit(new AuditEntry(Instant.now(), agent, model, hash, true, in, out,
				System.currentTimeMillis() - start, outcome));
		if (truncated) {
			throw new AgentException(agent + " superó el límite de tokens de salida (" + out
					+ " tokens): la respuesta quedó incompleta.", null);
		}
		if (text == null || text.isBlank()) {
			throw new AgentException(agent + " devolvió una respuesta vacía (motivo: " + finish + ").", null);
		}
		return text;
	}

	/** Por encima de este límite el SDK exige streaming para no agotar el tiempo de la petición HTTP. */
	private static final int NON_STREAMING_MAX_TOKENS = 32_000;

	private record Reply(String text, long inputTokens, long outputTokens, String finishReason) {
	}

	private static Reply called(ChatClient.ChatClientRequestSpec spec) {
		ChatResponse response = spec.call().chatResponse();
		Usage usage = response.getMetadata().getUsage();
		long in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
		long out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
		return new Reply(answerText(response), in, out, finishReason(List.of(response)));
	}

	/** Respuestas largas: se reciben por streaming y se reúnen al terminar. */
	private static Reply streamed(ChatClient.ChatClientRequestSpec spec) {
		List<ChatResponse> chunks = spec.stream().chatResponse().collectList().block(Duration.ofMinutes(20));
		if (chunks == null || chunks.isEmpty()) {
			return new Reply("", 0, 0, "sin resultado");
		}
		StringBuilder text = new StringBuilder();
		long in = 0;
		long out = 0;
		for (ChatResponse chunk : chunks) {
			for (var generation : chunk.getResults()) {
				if (Boolean.TRUE.equals(generation.getOutput().getMetadata().get("thinking"))) {
					continue;
				}
				String piece = generation.getOutput().getText();
				if (piece != null) {
					text.append(piece);
				}
			}
			Usage usage = chunk.getMetadata() == null ? null : chunk.getMetadata().getUsage();
			if (usage != null) {
				in = Math.max(in, usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
				out = Math.max(out, usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
			}
		}
		return new Reply(text.toString(), in, out, finishReason(chunks));
	}

	private static String finishReason(List<ChatResponse> responses) {
		return responses.stream()
			.flatMap(r -> r.getResults().stream())
			.map(g -> String.valueOf(g.getMetadata().getFinishReason()))
			.filter(f -> !"null".equals(f) && !f.isBlank())
			.reduce((first, second) -> second)
			.orElse("sin resultado");
	}

	/**
	 * Une los bloques de texto de la respuesta. Con razonamiento extendido el
	 * modelo devuelve primero bloques "thinking", que no forman parte de la
	 * respuesta y se descartan.
	 */
	private static String answerText(ChatResponse response) {
		return response.getResults()
			.stream()
			.filter(g -> !Boolean.TRUE.equals(g.getOutput().getMetadata().get("thinking")))
			.map(g -> g.getOutput().getText())
			.filter(t -> t != null && !t.isBlank())
			.collect(Collectors.joining("\n"));
	}

	private void saveRaw(MigrationJob job, String agent, String text) {
		try {
			Path dir = workspace.resolve("jobs").resolve(job.getId()).resolve("ai-responses");
			Files.createDirectories(dir);
			String name = "%02d-%s.txt".formatted(job.getAudit().size() + 1,
					agent.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
			Files.writeString(dir.resolve(name), text == null ? "" : text, StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			log.warn("No se pudo guardar la respuesta de {}", agent, ex);
		}
	}

	private static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
