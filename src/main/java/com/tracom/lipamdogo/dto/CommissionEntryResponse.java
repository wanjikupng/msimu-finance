package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.CommissionEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CommissionEntryResponse(
        Long id,
        String beneficiaryType,
        String beneficiaryName,
        String merchantName,
        String commissionType,
        String transactionReference,
        String terminalCode,
        String contractNumber,
        BigDecimal baseAmount,
        BigDecimal rate,
        BigDecimal amount,
        boolean clawback,
        String status,
        LocalDateTime earnedAt
) {
    public static CommissionEntryResponse from(CommissionEntry e) {
        var tx = e.getPaymentTransaction();
        return new CommissionEntryResponse(
                e.getId(),
                e.getBeneficiaryType().name(),
                e.getAgent() != null ? e.getAgent().getUserAccount().getFullName() : e.getMerchant().getBusinessName(),
                e.getMerchant().getBusinessName(),
                e.getCommissionType().name(),
                tx == null ? null : tx.getTransactionReference(),
                tx == null || tx.getTerminal() == null ? null : tx.getTerminal().getTerminalCode(),
                e.getContract() != null ? e.getContract().getContractNumber()
                        : tx == null ? null : tx.getContract().getContractNumber(),
                e.getBaseAmount(),
                e.getRate(),
                e.getAmount(),
                e.getReversesEntry() != null,
                e.getStatus().name(),
                e.getEarnedAt());
    }
}
