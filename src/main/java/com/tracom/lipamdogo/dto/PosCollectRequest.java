package com.tracom.lipamdogo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Agent-assisted M-PESA collection. {@code payerMsisdn} is the Safaricom
 * number that receives the payment prompt (07…, 01…, 2547…, +2547…).
 */
public record PosCollectRequest(
        @NotNull(message = "Contract is required.")
        Long contractId,

        @NotNull(message = "Amount is required.")
        @DecimalMin(value = "1", message = "Amount must be at least KES 1.")
        BigDecimal amount,

        @NotBlank(message = "M-PESA number is required.")
        @Pattern(regexp = "^(\\+?254|0)?[17]\\d{8}$", message = "Enter a valid Kenyan M-PESA number, e.g. 0712 345 678.")
        String payerMsisdn
) {
}
