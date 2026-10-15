package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TerminalRequest(
        @NotBlank(message = "Serial number is required.")
        @Size(min = 4, max = 64, message = "Serial number must be 4 to 64 characters.")
        @Pattern(regexp = "^[A-Za-z0-9\\-]+$", message = "Serial number may only contain letters, digits and dashes.")
        String serialNumber,

        @NotBlank(message = "Model is required.")
        @Size(max = 100, message = "Model must be at most 100 characters.")
        String model,

        @NotNull(message = "Merchant is required.")
        Long merchantId
) {
}
