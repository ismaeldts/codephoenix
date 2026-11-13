package com.challenge.codephoenix.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.challenge.codephoenix.config.CodephoenixProperties;
import com.challenge.codephoenix.domain.JobSnapshot;
import com.challenge.codephoenix.domain.MigrationJob;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Repositorio de migraciones en memoria respaldado en disco: cada migración se
 * guarda en {@code workspace/jobs/<id>/job.json} y se recupera al arrancar, de
 * modo que una demo completada puede reabrirse sin conexión.
 */
@Component
public class JobRepository {

	private static final Logger log = LoggerFactory.getLogger(JobRepository.class);
	private static final String SNAPSHOT_FILE = "job.json";

	private final Map<String, MigrationJob> jobs = new ConcurrentHashMap<>();
	private final Map<String, String> lastWritten = new ConcurrentHashMap<>();
	private final Path jobsDir;
	private final JsonMapper mapper;

	public JobRepository(CodephoenixProperties properties, JsonMapper mapper) {
		this.jobsDir = properties.workspace().resolve("jobs").toAbsolutePath();
		this.mapper = mapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
	}

	public void save(MigrationJob job) {
		jobs.put(job.getId(), job);
		persist(job);
	}

	public Optional<MigrationJob> find(String id) {
		return Optional.ofNullable(jobs.get(id));
	}

	public List<MigrationJob> findAll() {
		return jobs.values().stream().sorted(Comparator.comparing(MigrationJob::getCreatedAt).reversed()).toList();
	}

	@PostConstruct
	void load() {
		if (!Files.isDirectory(jobsDir)) {
			return;
		}
		try (Stream<Path> dirs = Files.list(jobsDir)) {
			for (Path dir : dirs.filter(Files::isDirectory).toList()) {
				Path file = dir.resolve(SNAPSHOT_FILE);
				if (Files.exists(file)) {
					restore(file);
				}
			}
		}
		catch (IOException ex) {
			log.warn("No se pudo recorrer {}", jobsDir, ex);
		}
		log.info("{} migraciones recuperadas de {}", jobs.size(), jobsDir);
	}

	private void restore(Path file) {
		try {
			JobSnapshot snapshot = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), JobSnapshot.class);
			Path legacy = file.getParent().resolve("legacy").resolve(snapshot.sourceName());
			String originalCode = Files.exists(legacy) ? Files.readString(legacy, StandardCharsets.UTF_8) : "";
			MigrationJob job = MigrationJob.restore(snapshot, originalCode);
			if (job.getStatus() == MigrationJob.Status.EN_CURSO) {
				job.interrupted("Migración interrumpida: el servidor se detuvo mientras los agentes trabajaban. "
						+ "Lanza una nueva migración con el mismo artefacto.");
			}
			jobs.put(job.getId(), job);
			persist(job);
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Se omite la migración guardada en {}: {}", file, ex.getMessage());
		}
	}

	/** Guarda en disco las migraciones que cambiaron desde la última escritura. */
	@Scheduled(fixedDelay = 2000)
	@PreDestroy
	void flush() {
		jobs.values().forEach(this::persist);
	}

	private void persist(MigrationJob job) {
		try {
			String json = mapper.writeValueAsString(job.snapshot());
			if (json.equals(lastWritten.get(job.getId()))) {
				return;
			}
			Path dir = jobsDir.resolve(job.getId());
			Files.createDirectories(dir);
			Path tmp = dir.resolve(SNAPSHOT_FILE + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, dir.resolve(SNAPSHOT_FILE), StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex) {
				Files.move(tmp, dir.resolve(SNAPSHOT_FILE), StandardCopyOption.REPLACE_EXISTING);
			}
			lastWritten.put(job.getId(), json);
		}
		catch (IOException | RuntimeException ex) {
			log.warn("No se pudo guardar la migración {}", job.getId(), ex);
		}
	}

}
