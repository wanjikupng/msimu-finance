package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.ProviderTransactionStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProviderFeedItem(
        @NotNull(message = "Provider is required.")
        PaymentChannel provider,

        @NotBlank(message = "Provider reference is required.")
        @Size(max = 255, message = "Provider reference must be at most 255 characters.")
        String providerReference,

        @NotNull(message = "Amount is required.")
        @DecimalMin(value = "0.00", message = "Amount cannot be negative.")
        BigDecimal amount,

        @Size(max = 10, message = "Currency must be at most 10 characters.")
        String currency,

        @NotNull(message = "Transaction time is required.")
        LocalDateTime transactionTime,

        @NotNull(message = "Status is required.")
        ProviderTransactionStatus status,

        @Size(max = 500, message = "Metadata must be at most 500 characters.")
        String rawMetadata
) {
}
