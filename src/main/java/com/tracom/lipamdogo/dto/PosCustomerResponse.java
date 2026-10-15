package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Customer lookup at a terminal. Deliberately minimal: an agent sees enough
 * to confirm who is in front of them and what is due, nothing more.
 */
public record PosCustomerResponse(
        String displayName,
        String maskedPhone,
        String payerMsisdn,
        List<Contract> contracts
) {
    public record Contract(
            Long contractId,
            String contractNumber,
            String productName,
            String deviceCode,
            String status,
            String lockState,
            BigDecimal dailyAmount,
            BigDecimal amountDueNow,
            BigDecimal amountRemaining,
            LocalDate nextPaymentDueDate
    ) {
    }
}
