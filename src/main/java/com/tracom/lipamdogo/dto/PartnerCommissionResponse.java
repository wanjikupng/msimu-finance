package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An agent's or merchant's own commission view: what is owed, what is on its
 * way, what has been paid to their M-PESA, and the entries behind it.
 * For a merchant, {@code agents} breaks the figures down per agent.
 */
public record PartnerCommissionResponse(
        CommissionPlanResponse plan,
        BigDecimal owed,
        BigDecimal inSettlement,
        BigDecimal paidToDate,
        BigDecimal earnedToday,
        List<CommissionEntryResponse> entries,
        List<SettlementLineResponse> payouts,
        List<BeneficiaryBalanceResponse> agents
) {
}
