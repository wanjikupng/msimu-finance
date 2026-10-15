package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Settle every unpaid commission earned up to and including {@code cutoffDate}.
 */
public record SettlementRunRequest(
        @NotNull(message = "Cut-off date is required.")
        LocalDate cutoffDate
) {
}
