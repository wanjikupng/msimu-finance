package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.DeviceStatus;
import jakarta.validation.constraints.NotNull;

public record DeviceStatusRequest(
        @NotNull(message = "Status is required.")
        DeviceStatus status
) {
}
