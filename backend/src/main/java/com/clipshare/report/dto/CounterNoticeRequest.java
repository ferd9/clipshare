package com.clipshare.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CounterNoticeRequest(
        @NotBlank String statement,
        @NotNull Boolean consentToJurisdiction,
        @NotBlank String signature
) {
}
