package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record DashboardResponse(
        UserProfileResponse customer,
        List<LoanContractResponse> deviceLoans,
        List<CashLoanResponse> cashLoans,
        BigDecimal totalBalance,
        BigDecimal totalPaid,
        Map<String, Object> paymentSummary,
        List<PaymentTimelineEntry> paymentTimeline
) {
}
