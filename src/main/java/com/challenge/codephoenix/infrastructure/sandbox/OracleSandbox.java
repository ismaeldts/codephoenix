package com.challenge.codephoenix.infrastructure.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.challenge.codephoenix.config.CodephoenixProperties;

/**
 * Base de datos Oracle Free en un contenedor "caliente" que se reutiliza entre
 * migraciones. Está en una red Docker interna: tiene IP privada (Oracle la
 * necesita para arrancar) pero ninguna salida a Internet. Cada migración usa
 * su propio usuario/esquema, que se recrea en cada ejecución.
 */
@Component
public class OracleSandbox {

	public enum Availability { SIN_IMAGEN, DETENIDO, INICIANDO, LISTO }

	public record Result(int exitCode, String output, boolean timedOut) {

		public boolean succeeded() {
			return exitCode == 0 && !timedOut;
		}

	}

	private static final Logger log = LoggerFactory.getLogger(OracleSandbox.class);
	private static final String LABEL = "codephoenix.jobs";

	private final CodephoenixProperties.Oracle config;
	private final Path jobsRoot;
	private volatile boolean starting;

	public OracleSandbox(CodephoenixProperties properties) {
		this.config = properties.oracle();
		this.jobsRoot = properties.workspace().resolve("jobs").toAbsolutePath();
	}

	/** Arranca la base de datos en segundo plano al iniciar la aplicación, para que esté lista cuando se necesite. */
	@EventListener(ApplicationReadyEvent.class)
	public void warmUp() {
		if (!config.warmUp() || !imagePresent()) {
			return;
		}
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				ensureReady();
				log.info("Sandbox Oracle listo ({})", config.container());
			}
			catch (Exception ex) {
				log.warn("No se pudo preparar el sandbox Oracle: {}", ex.getMessage());
			}
		});
	}

	public Availability availability() {
		if (!imagePresent()) {
			return Availability.SIN_IMAGEN;
		}
		if (starting) {
			return Availability.INICIANDO;
		}
		return "true".equals(inspect("{{.State.Running}}")) ? Availability.LISTO : Availability.DETENIDO;
	}

	/** Garantiza que el contenedor existe, está en marcha y acepta conexiones. */
	public synchronized void ensureReady() throws IOException, InterruptedException {
		if (!imagePresent()) {
			throw new IllegalStateException("Falta la imagen de Oracle. Descárgala con: docker pull " + config.image());
		}
		starting = true;
		try {
			// Sin comillas dentro de la plantilla: en Windows ProcessBuilder no las escapa bien.
			String labels = inspect("{{json .Config.Labels}}");
			boolean exists = labels != null;
			String expected = "\"" + LABEL + "\":\"" + jobsRoot.toString().replace("\\", "\\\\") + "\"";
			if (exists && !labels.contains(expected)) {
				log.info("El contenedor {} monta otro workspace; se recrea", config.container());
				docker(List.of("rm", "-f", config.container()), Duration.ofMinutes(1));
				exists = false;
			}
			if (!exists) {
				Files.createDirectories(jobsRoot);
				docker(List.of("network", "create", "--internal", config.network()), Duration.ofMinutes(1));
				Result run = docker(List.of("run", "-d", "--name", config.container(), "--network", config.network(),
						"--memory", "2g", "--label", LABEL + "=" + jobsRoot, "-e", "ORACLE_PASSWORD=" + randomSecret(),
						"-v", jobsRoot + ":/work/jobs", config.image()), Duration.ofMinutes(2));
				if (!run.succeeded()) {
					throw new IllegalStateException("No se pudo crear el contenedor Oracle: " + run.output());
				}
			}
			else if (!"true".equals(inspect("{{.State.Running}}"))) {
				docker(List.of("start", config.container()), Duration.ofMinutes(1));
			}
			waitUntilAcceptingConnections();
		}
		finally {
			starting = false;
		}
	}

	/** Ejecuta un script bash dentro del contenedor (la variable ORACLE_PASSWORD está disponible). */
	public Result exec(Path jobDir, String script) throws IOException, InterruptedException {
		ensureReady();
		Path file = jobDir.resolve("plsql").resolve("run.sh");
		Files.createDirectories(file.getParent());
		Files.writeString(file, script.replace("\r\n", "\n"), StandardCharsets.UTF_8);
		String inContainer = "/work/jobs/" + jobsRoot.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
		return docker(List.of("exec", config.container(), "bash", inContainer), config.timeout());
	}

	private void waitUntilAcceptingConnections() throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + config.startupTimeout().toMillis();
		// Sin comillas dobles: en Windows ProcessBuilder no las escapa dentro de un argumento.
		String probe = "echo 'SELECT ''CP_OK'' FROM dual;' | sqlplus -s -L system/${ORACLE_PASSWORD}@//localhost/FREEPDB1";
		while (System.currentTimeMillis() < deadline) {
			Result r = docker(List.of("exec", config.container(), "bash", "-c", probe), Duration.ofSeconds(30));
			if (r.output().contains("CP_OK")) {
				return;
			}
			if (!"true".equals(inspect("{{.State.Running}}"))) {
				Result logs = docker(List.of("logs", "--tail", "20", config.container()), Duration.ofSeconds(30));
				throw new IllegalStateException("El contenedor Oracle se detuvo al arrancar:\n" + logs.output());
			}
			Thread.sleep(2000);
		}
		throw new IllegalStateException("Oracle no aceptó conexiones en " + config.startupTimeout().toSeconds() + " s");
	}

	private boolean imagePresent() {
		try {
			return docker(List.of("image", "inspect", config.image()), Duration.ofSeconds(30)).succeeded();
		}
		catch (IOException ex) {
			return false;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private String inspect(String format) {
		try {
			Result r = docker(List.of("inspect", "-f", format, config.container()), Duration.ofSeconds(30));
			return r.succeeded() ? r.output().strip() : null;
		}
		catch (IOException ex) {
			return null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private static Result docker(List<String> args, Duration timeout) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add("docker");
		command.addAll(args);
		Path log = Files.createTempFile("oracle", ".log");
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
			boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!finished) {
				process.destroyForcibly();
			}
			return new Result(finished ? process.exitValue() : -1, Files.readString(log, StandardCharsets.UTF_8),
					!finished);
		}
		finally {
			Files.deleteIfExists(log);
		}
	}

	static String randomSecret() {
		String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
		SecureRandom random = new SecureRandom();
		StringBuilder sb = new StringBuilder("Cp");
		for (int i = 0; i < 20; i++) {
			sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return sb.toString();
	}

}
