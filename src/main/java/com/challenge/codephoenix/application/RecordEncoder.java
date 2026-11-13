package com.challenge.codephoenix.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.challenge.codephoenix.domain.StaticAnalysis.RecordField;

/**
 * Codifica valores legibles en el registro de ancho fijo que espera el
 * programa legacy (dígitos con decimales implícitos, rellenos con ceros).
 */
public final class RecordEncoder {

	private RecordEncoder() {
	}

	/** Devuelve el registro, o vacío si falta un campo o un valor no cabe en su PIC. */
	public static Optional<String> encode(List<RecordField> layout, Map<String, String> values) {
		if (layout.isEmpty() || values == null) {
			return Optional.empty();
		}
		StringBuilder record = new StringBuilder();
		for (RecordField field : layout) {
			String raw = values.get(field.name());
			if (raw == null) {
				return Optional.empty();
			}
			BigDecimal value;
			try {
				value = new BigDecimal(raw.strip().replace(",", "."));
			}
			catch (NumberFormatException ex) {
				return Optional.empty();
			}
			if (value.signum() < 0 || value.stripTrailingZeros().scale() > field.decimals()) {
				return Optional.empty();
			}
			String digits = value.movePointRight(field.decimals()).toBigInteger().toString();
			if (digits.length() > field.length()) {
				return Optional.empty();
			}
			record.append("0".repeat(field.length() - digits.length())).append(digits);
		}
		return Optional.of(record.toString());
	}

}
