package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.TerminalStatus;
import jakarta.validation.constraints.NotNull;

public record TerminalStatusRequest(
        @NotNull(message = "Status is required.")
        TerminalStatus status
) {
}
