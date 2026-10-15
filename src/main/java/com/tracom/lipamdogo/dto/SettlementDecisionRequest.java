package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.Size;

public record SettlementDecisionRequest(
        @Size(max = 500, message = "Note must be at most 500 characters.")
        String note
) {
}
