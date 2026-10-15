package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A plan is a repayment term. Installments are computed per contract from the
 * financed amount and this duration; deposit/daily figures are optional,
 * indicative values only.
 */
public record PaymentPlanRequest(
        @NotBlank(message = "Plan name is required.")
        @Size(max = 255, message = "Plan name must be at most 255 characters.")
        String planName,

        @NotNull(message = "Duration is required.")
        @Min(value = 7, message = "Duration must be at least 7 days.")
        @Max(value = 730, message = "Duration must be at most 730 days.")
        Integer durationDays,

        @DecimalMin(value = "0.00", message = "Deposit cannot be negative.")
        BigDecimal depositAmount,

        @DecimalMin(value = "0.00", message = "Daily amount cannot be negative.")
        BigDecimal dailyAmount
) {
}
