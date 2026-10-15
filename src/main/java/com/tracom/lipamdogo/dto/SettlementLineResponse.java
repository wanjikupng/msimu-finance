package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.SettlementLine;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SettlementLineResponse(
        Long id,
        String batchNumber,
        String beneficiaryType,
        String payeeName,
        String merchantName,
        String payeeMsisdn,
        String payoutMethod,
        int entryCount,
        BigDecimal earnedAmount,
        BigDecimal clawbackAmount,
        BigDecimal netAmount,
        String status,
        String payoutReference,
        String failureReason,
        LocalDateTime paidAt
) {
    public static SettlementLineResponse from(SettlementLine l) {
        return new SettlementLineResponse(
                l.getId(),
                l.getBatch().getBatchNumber(),
                l.getBeneficiaryType().name(),
                l.getPayeeName(),
                l.getMerchant().getBusinessName(),
                PaymentTransactionResponse.maskMsisdn(l.getPayeeMsisdn()),
                l.getPayoutMethod(),
                l.getEntryCount(),
                l.getEarnedAmount(),
                l.getClawbackAmount(),
                l.getNetAmount(),
                l.getStatus().name(),
                l.getPayoutReference(),
                l.getFailureReason(),
                l.getPaidAt());
    }
}
