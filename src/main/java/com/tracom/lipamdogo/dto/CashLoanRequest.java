package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CashLoanRequest(
        @NotNull(message = "Loan amount is required.")
        @Positive(message = "Loan amount must be greater than zero.")
        BigDecimal amountRequested,

        BigDecimal monthlyRate,

        @Size(max = 255, message = "Purpose must be at most 255 characters.")
        String purpose
) {
}
