package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;

public record PaymentPlanRequest(
        String planName,
        Integer durationDays,
        BigDecimal depositAmount,
        BigDecimal dailyAmount
) {
}
