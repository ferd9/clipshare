package com.clipshare.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCommentRequest(
        @NotBlank @Size(min = 1, max = 500) String body,
        String turnstileToken,          // requerido si es GUEST, ver docs/SPEC.md sección 11.4
        UUID parentCommentId            // opcional, hilo de respuesta
) {
}
