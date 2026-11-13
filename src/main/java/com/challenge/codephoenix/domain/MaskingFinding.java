package com.challenge.codephoenix.domain;

/** Dato sensible detectado y enmascarado antes de enviar el código al modelo. */
public record MaskingFinding(String type, int line, String replacement) {
}
