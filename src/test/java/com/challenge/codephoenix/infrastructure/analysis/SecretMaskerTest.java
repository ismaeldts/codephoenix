package com.challenge.codephoenix.infrastructure.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.challenge.codephoenix.domain.MaskingFinding;

class SecretMaskerTest {

	private final SecretMasker masker = new SecretMasker();

	@Test
	void masksCredentialsConnectionStringsAndEmailsInSample() throws IOException {
		String code = new ClassPathResource("samples/AMORTIZA.cbl").getContentAsString(StandardCharsets.UTF_8);

		SecretMasker.Result result = masker.mask(code);

		List<String> types = result.findings().stream().map(MaskingFinding::type).toList();
		assertEquals(List.of("EMAIL", "CADENA_CONEXION", "USUARIO", "CONTRASENA"), types);
		assertFalse(result.maskedCode().contains("Cr3d1t0s#2019"));
		assertFalse(result.maskedCode().contains("ADMCRED"));
		assertFalse(result.maskedCode().contains("jperez.creditos"));
		assertEquals(code.split("\r?\n", -1).length, result.maskedCode().split("\n", -1).length);
		assertTrue(masker.mask(result.maskedCode()).findings().isEmpty());
	}

	@Test
	void masksPlsqlLiteralAssignmentsButNotVariableAssignments() {
		SecretMasker.Result result = masker.mask("v_password := 'S3creta!';\nv_usuario := p_usuario;\nuser=LIQUIDADOR");

		assertEquals("v_password := '[CONTRASENA_1]';\nv_usuario := p_usuario;\nuser=[USUARIO_1]", result.maskedCode());
	}

	@Test
	void masksOnlyCardNumbersThatPassLuhn() {
		SecretMasker.Result result = masker.mask("TARJETA 4111111111111111 Y CUENTA 1234567890123");

		assertEquals(1, result.findings().size());
		assertEquals("TARJETA [TARJETA_1] Y CUENTA 1234567890123", result.maskedCode());
	}

}
