package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.payment.ProviderOutcome;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProviderResultRequest(
        @NotNull(message = "Outcome is required.")
        ProviderOutcome outcome,

        @Size(max = 500, message = "Failure reason must be at most 500 characters.")
        String failureReason
) {
}
