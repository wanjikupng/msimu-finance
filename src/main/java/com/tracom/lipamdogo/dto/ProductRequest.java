package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;

public record ProductRequest(
        String name,
        String description,
        BigDecimal fullPrice,
        BigDecimal downPayment,
        BigDecimal dailyAmount,
        String paymentPlanName,
        String paymentMode
) {
}
