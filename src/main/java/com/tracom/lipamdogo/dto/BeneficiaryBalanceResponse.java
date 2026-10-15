package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;

/**
 * What one agent or merchant is owed: waiting for the next run, in a batch
 * awaiting approval, and already paid out.
 */
public record BeneficiaryBalanceResponse(
        String beneficiaryType,
        Long agentId,
        Long merchantId,
        String name,
        String merchantName,
        BigDecimal accrued,
        BigDecimal inSettlement,
        BigDecimal settled
) {
}
