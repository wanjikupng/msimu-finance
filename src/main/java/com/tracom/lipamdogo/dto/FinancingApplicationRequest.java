package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record FinancingApplicationRequest(
        @NotNull(message = "Device is required.")
        Long deviceId,

        @NotNull(message = "Deposit is required.")
        @DecimalMin(value = "0.00", message = "Deposit cannot be negative.")
        BigDecimal depositAmount,

        @NotNull(message = "Payment plan is required.")
        Long paymentPlanId,

        Long paymentModeId
) {
}
