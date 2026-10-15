package com.tracom.lipamdogo.payment;

import java.math.BigDecimal;

public record ProviderPaymentRequest(
        Long transactionId,
        String transactionReference,
        BigDecimal amount,
        Long contractId,
        Long customerId,
        MockProviderScenario mockScenario
) {
}
