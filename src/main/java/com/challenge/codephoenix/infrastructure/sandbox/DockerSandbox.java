package com.challenge.codephoenix.infrastructure.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.challenge.codephoenix.config.CodephoenixProperties;

/**
 * Ejecuta scripts dentro de un contenedor efímero sin red y con recursos
 * limitados. Es la única vía por la que se ejecuta el código legacy y el
 * código generado por la IA.
 */
@Component
public class DockerSandbox {

	public record Result(int exitCode, String output, boolean timedOut) {

		public boolean succeeded() {
			return exitCode == 0 && !timedOut;
		}

	}

	private final CodephoenixProperties properties;

	public DockerSandbox(CodephoenixProperties properties) {
		this.properties = properties;
	}

	public boolean isAvailable() {
		try {
			Process process = new ProcessBuilder("docker", "image", "inspect", properties.sandbox().image())
				.redirectErrorStream(true)
				.start();
			process.getInputStream().readAllBytes();
			return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
		}
		catch (IOException ex) {
			return false;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public Result run(Path workDir, String script) throws IOException, InterruptedException {
		Path scriptFile = workDir.resolve(".sandbox-run.sh");
		Files.writeString(scriptFile, script.replace("\r\n", "\n"), StandardCharsets.UTF_8);
		List<String> command = List.of("docker", "run", "--rm", "--network", "none", "--memory", "1g", "--cpus",
				"2", "--pids-limit", "512", "-v", workDir.toAbsolutePath() + ":/work", "-w", "/work",
				properties.sandbox().image(), "bash", "/work/.sandbox-run.sh");
		Path log = Files.createTempFile("sandbox", ".log");
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
			boolean finished = process.waitFor(properties.sandbox().timeout().toMillis(), TimeUnit.MILLISECONDS);
			if (!finished) {
				process.destroyForcibly();
			}
			String output = Files.readString(log, StandardCharsets.UTF_8);
			return new Result(finished ? process.exitValue() : -1, output, !finished);
		}
		finally {
			Files.deleteIfExists(log);
			Files.deleteIfExists(scriptFile);
		}
	}

}
