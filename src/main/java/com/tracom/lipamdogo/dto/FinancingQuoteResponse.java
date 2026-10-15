package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.PaymentFrequency;

import java.math.BigDecimal;

/**
 * Server-authoritative financing quote for display. The frontend shows these
 * figures; it never decides the terms — submission re-validates everything.
 */
public record FinancingQuoteResponse(
        Long deviceId,
        String deviceCode,
        String productName,
        BigDecimal devicePrice,
        BigDecimal depositAmount,
        BigDecimal requestedPrincipal,
        BigDecimal installmentAmount,
        PaymentFrequency paymentFrequency,
        String paymentPlanName,
        Integer planDurationDays,
        BigDecimal totalPayable
) {
}
