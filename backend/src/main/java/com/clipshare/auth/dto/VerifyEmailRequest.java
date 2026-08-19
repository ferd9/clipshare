package com.clipshare.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(
        @NotBlank String token
) {
}
