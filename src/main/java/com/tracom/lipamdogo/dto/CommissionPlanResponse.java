package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;

/**
 * The commission plan in force. Rates are fractions (0.02 = 2%).
 */
public record CommissionPlanResponse(
        BigDecimal agentCollectionRate,
        BigDecimal agentCollectionCap,
        BigDecimal merchantCollectionRate,
        BigDecimal merchantSaleFee
) {
}
