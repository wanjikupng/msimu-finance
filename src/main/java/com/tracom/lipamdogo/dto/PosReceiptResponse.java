package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The slip a terminal prints after an M-PESA collection.
 */
public record PosReceiptResponse(
        String status,
        String merchantName,
        String terminalCode,
        String agentName,
        String transactionReference,
        String mpesaReference,
        String receiptNumber,
        String customerName,
        String payerMsisdn,
        String contractNumber,
        String productName,
        BigDecimal amount,
        BigDecimal balanceAfter,
        LocalDate nextPaymentDueDate,
        String unlockCode,
        LocalDate unlockedUntil,
        boolean unlockPermanent,
        String failureReason,
        LocalDateTime requestedAt,
        LocalDateTime completedAt
) {
}
