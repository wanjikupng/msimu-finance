package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectApplicationRequest(
        @NotBlank(message = "A decision reason is required.")
        @Size(max = 500, message = "Decision reason must be at most 500 characters.")
        String decisionReason
) {
}
