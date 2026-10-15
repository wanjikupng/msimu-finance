package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.PaymentReceipt;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReceiptResponse(
        String receiptNumber,
        String transactionReference,
        String providerReference,
        String customerName,
        String customerCode,
        String contractNumber,
        String deviceCode,
        String productName,
        BigDecimal amount,
        PaymentChannel channel,
        PaymentTransactionStatus status,
        LocalDateTime postedAt,
        BigDecimal remainingBalance
) {
    public static ReceiptResponse from(PaymentReceipt receipt) {
        var transaction = receipt.getTransaction();
        var contract = transaction.getContract();
        return new ReceiptResponse(
                receipt.getReceiptNumber(),
                transaction.getTransactionReference(),
                transaction.getProviderReference(),
                transaction.getCustomer().getFullName(),
                transaction.getCustomer().getCustomerCode(),
                contract.getContractNumber(),
                contract.getDevice() == null ? null : contract.getDevice().getDeviceCode(),
                contract.getProduct().getName(),
                receipt.getAmountApplied(),
                transaction.getChannel(),
                transaction.getStatus(),
                receipt.getPostedAt(),
                receipt.getBalanceAfter()
        );
    }
}
