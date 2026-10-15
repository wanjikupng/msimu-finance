package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeviceRequest(
        @NotNull(message = "Product is required.")
        Long productId,

        @Pattern(regexp = "^\\d{10,16}$", message = "IMEI must be 10 to 16 digits when provided.")
        String imei,

        @Size(min = 3, max = 64, message = "Serial number must be between 3 and 64 characters when provided.")
        @Pattern(regexp = "^[A-Za-z0-9\\-]+$", message = "Serial number may only contain letters, digits and dashes.")
        String serialNumber
) {
}
