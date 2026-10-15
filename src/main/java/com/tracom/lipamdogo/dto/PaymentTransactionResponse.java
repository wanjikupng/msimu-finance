package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentTransactionResponse(
        String transactionReference,
        PaymentTransactionStatus status,
        BigDecimal amount,
        PaymentChannel channel,
        Long contractId,
        String contractNumber,
        String customerCode,
        String customerName,
        String providerReference,
        String failureReason,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        LocalDateTime postedAt,
        String receiptNumber,
        BigDecimal remainingBalance,
        String reversalReason
) {
    public static PaymentTransactionResponse from(PaymentTransaction transaction, String receiptNumber) {
        return new PaymentTransactionResponse(
                transaction.getTransactionReference(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getChannel(),
                transaction.getContract().getId(),
                transaction.getContract().getContractNumber(),
                transaction.getCustomer().getCustomerCode(),
                transaction.getCustomer().getFullName(),
                transaction.getProviderReference(),
                transaction.getFailureReason(),
                transaction.getRequestedAt(),
                transaction.getCompletedAt(),
                transaction.getPostedAt(),
                receiptNumber,
                transaction.getContract().getAmountRemaining(),
                transaction.getReversalReason()
        );
    }
}
