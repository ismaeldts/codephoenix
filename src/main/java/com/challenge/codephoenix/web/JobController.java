package com.challenge.codephoenix.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.challenge.codephoenix.application.InvalidRequestException;
import com.challenge.codephoenix.application.JobNotFoundException;
import com.challenge.codephoenix.application.JobRepository;
import com.challenge.codephoenix.application.MigrationOrchestrator;
import com.challenge.codephoenix.domain.BusinessRule;
import com.challenge.codephoenix.domain.MigrationJob;
import com.challenge.codephoenix.infrastructure.sandbox.DockerSandbox;
import com.challenge.codephoenix.infrastructure.sandbox.OracleSandbox;

@RestController
@RequestMapping("/api")
public class JobController {

	public record StartRequest(String sourceName, String code, String domainName, String targetStack,
			List<String> caseInputs) {
	}

	public record DecisionRequest(String decision, String description, String condition, String action,
			String comment) {
	}

	public record Sample(String id, String fileName, String language, String domain, String description) {
	}

	private static final List<Sample> SAMPLES = List.of(
			new Sample("amortiza", "AMORTIZA.cbl", "COBOL", "Créditos de consumo — amortización francesa",
					"Cuota fija con ajuste de tasa por score, tope de usura, seguro de desgravamen, COMP-3, GO TO "
							+ "y código muerto. Incluye credenciales falsas para demostrar el enmascaramiento."),
			new Sample("siniestro", "LIQUIDA_SINIESTRO.sql", "PL/SQL", "Seguros generales — liquidación de siniestros",
					"Procedimiento con su DDL: carencias por producto, exclusiones, deducible, coaseguro, sublímite, "
							+ "retención, GOTO, código muerto y efectos sobre tablas. Se ejecuta en Oracle Database Free."));

	private final MigrationOrchestrator orchestrator;
	private final JobRepository repository;
	private final DockerSandbox sandbox;
	private final OracleSandbox oracle;

	public JobController(MigrationOrchestrator orchestrator, JobRepository repository, DockerSandbox sandbox,
			OracleSandbox oracle) {
		this.orchestrator = orchestrator;
		this.repository = repository;
		this.sandbox = sandbox;
		this.oracle = oracle;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		return Map.of("sandboxAvailable", sandbox.isAvailable(), "oracle", oracle.availability());
	}

	@GetMapping("/samples")
	public List<Sample> samples() {
		return SAMPLES;
	}

	@GetMapping(value = "/samples/{id}", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
	public String sample(@PathVariable String id) throws IOException {
		Sample sample = SAMPLES.stream()
			.filter(s -> s.id().equals(id))
			.findFirst()
			.orElseThrow(() -> new InvalidRequestException("No existe el ejemplo " + id));
		return new ClassPathResource("samples/" + sample.fileName()).getContentAsString(StandardCharsets.UTF_8);
	}

	@PostMapping("/jobs")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
	public MigrationJob start(@RequestBody StartRequest request) {
		return orchestrator.start(request.sourceName(), request.code(), request.domainName(), request.targetStack(),
				request.caseInputs());
	}

	@GetMapping("/jobs")
	public List<Map<String, Object>> jobs() {
		return repository.findAll()
			.stream()
			.map(j -> {
				Map<String, Object> summary = new java.util.LinkedHashMap<>();
				summary.put("id", j.getId());
				summary.put("sourceName", j.getSourceName());
				summary.put("domainName", j.getDomainName());
				summary.put("status", j.getStatus());
				summary.put("createdAt", j.getCreatedAt());
				summary.put("rules", j.getRules().size());
				summary.put("equivalence", j.getEquivalence() == null ? null
						: j.getEquivalence().passed() + "/" + j.getEquivalence().total());
				summary.put("packageReady", j.isPackageReady());
				return summary;
			})
			.toList();
	}

	@GetMapping("/jobs/{id}")
	public MigrationJob job(@PathVariable String id) {
		return orchestrator.requireJob(id);
	}

	@PostMapping("/jobs/{id}/rules/{ruleId}")
	public BusinessRule decide(@PathVariable String id, @PathVariable String ruleId,
			@RequestBody DecisionRequest request) {
		return orchestrator.decide(id, ruleId, request.decision(), request.description(), request.condition(),
				request.action(), request.comment());
	}

	@PostMapping("/jobs/{id}/approve")
	public MigrationJob approve(@PathVariable String id) {
		orchestrator.approve(id);
		return orchestrator.requireJob(id);
	}

	@GetMapping("/jobs/{id}/package")
	public ResponseEntity<Resource> download(@PathVariable String id) {
		MigrationJob job = orchestrator.requireJob(id);
		if (job.getPackageFile() == null) {
			throw new InvalidRequestException("El paquete todavía no está listo.");
		}
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION,
					"attachment; filename=\"" + job.getPackageFile().getFileName() + "\"")
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.body(new FileSystemResource(job.getPackageFile()));
	}

	@ExceptionHandler(InvalidRequestException.class)
	public ResponseEntity<Map<String, String>> badRequest(InvalidRequestException ex) {
		return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(JobNotFoundException.class)
	public ResponseEntity<Map<String, String>> notFound(JobNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
	}

}
