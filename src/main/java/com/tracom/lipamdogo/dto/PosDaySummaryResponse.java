package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * End-of-day report for one terminal. Cashless: it lists M-PESA collections
 * only; the money is already in the business's M-PESA account, so there is
 * nothing for the agent to count or hand over.
 */
public record PosDaySummaryResponse(
        String terminalCode,
        String merchantName,
        String agentName,
        LocalDate businessDate,
        long approvedCount,
        BigDecimal approvedAmount,
        long declinedCount,
        long pendingCount,
        List<PaymentTransactionResponse> transactions
) {
}
