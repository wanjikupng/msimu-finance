package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.NotNull;

public record AgentRequest(
        @NotNull(message = "User account is required.")
        Long userAccountId,

        @NotNull(message = "Merchant is required.")
        Long merchantId
) {
}
