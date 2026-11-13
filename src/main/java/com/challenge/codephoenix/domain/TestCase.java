package com.challenge.codephoenix.domain;

import java.util.List;

/** Caso de caracterización: una entrada que se ejecuta contra el legacy y contra el código moderno. */
public record TestCase(String id, String description, String kind, String input, List<String> ruleIds) {
}
