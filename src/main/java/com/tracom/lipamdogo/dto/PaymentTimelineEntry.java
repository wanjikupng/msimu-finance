package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentTimelineEntry(
        String transactionReference,
        LocalDateTime date,
        BigDecimal amount,
        PaymentChannel channel,
        PaymentTransactionStatus status,
        String failureReason,
        String receiptNumber,
        BigDecimal balanceAfter
) {
    public static PaymentTimelineEntry from(PaymentTransaction transaction, String receiptNumber,
                                            BigDecimal balanceAfter) {
        return new PaymentTimelineEntry(
                transaction.getTransactionReference(),
                transaction.getRequestedAt(),
                transaction.getAmount(),
                transaction.getChannel(),
                transaction.getStatus(),
                transaction.getFailureReason(),
                receiptNumber,
                balanceAfter
        );
    }
}
