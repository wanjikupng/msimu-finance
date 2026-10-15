package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.NotNull;

public record TerminalAssignRequest(
        @NotNull(message = "Agent is required.")
        Long agentId
) {
}
