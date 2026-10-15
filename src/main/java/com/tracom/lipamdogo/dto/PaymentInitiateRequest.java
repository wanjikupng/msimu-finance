package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.payment.MockProviderScenario;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentInitiateRequest(
        @NotNull(message = "Contract is required.")
        Long contractId,

        @NotNull(message = "Payment amount is required.")
        @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero.")
        BigDecimal amount,

        @NotNull(message = "Payment channel is required.")
        PaymentChannel channel,

        /**
         * Simulation control honored only by mock providers. Real adapters
         * ignore it. Defaults to AUTO.
         */
        MockProviderScenario mockScenario
) {
}
