package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReverseTransactionRequest(
        @NotBlank(message = "A reversal reason is required.")
        @Size(max = 500, message = "Reversal reason must be at most 500 characters.")
        String reason
) {
}
