package com.challenge.codephoenix.domain;

import java.time.Instant;

public record LogEvent(Instant at, String phase, String level, String message) {
}
