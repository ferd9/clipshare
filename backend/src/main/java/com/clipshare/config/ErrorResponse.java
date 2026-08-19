package com.clipshare.config;

/** Formato consistente de error para todas las respuestas de la API (docs/SPEC.md sección 8). */
public record ErrorResponse(String error, String message) {
}
