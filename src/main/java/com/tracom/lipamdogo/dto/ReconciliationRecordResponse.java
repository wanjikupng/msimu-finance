package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.ProviderTransactionStatus;
import com.tracom.lipamdogo.model.ReconciliationRecord;
import com.tracom.lipamdogo.model.ReconciliationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReconciliationRecordResponse(
        Long id,
        Long batchId,
        String batchNumber,
        ReconciliationStatus status,
        String providerReference,
        PaymentChannel provider,
        BigDecimal providerAmount,
        ProviderTransactionStatus providerStatus,
        String internalReference,
        BigDecimal internalAmount,
        String internalStatus,
        BigDecimal differenceAmount,
        String reason,
        LocalDateTime reconciledAt
) {
    public static ReconciliationRecordResponse from(ReconciliationRecord record) {
        var providerTx = record.getProviderTransaction();
        var internalTx = record.getPaymentTransaction();
        return new ReconciliationRecordResponse(
                record.getId(),
                record.getBatch().getId(),
                record.getBatch().getBatchNumber(),
                record.getStatus(),
                providerTx == null ? null : providerTx.getProviderReference(),
                providerTx == null ? null : providerTx.getProvider(),
                providerTx == null ? null : providerTx.getAmount(),
                providerTx == null ? null : providerTx.getStatus(),
                internalTx == null ? null : internalTx.getTransactionReference(),
                internalTx == null ? null : internalTx.getAmount(),
                internalTx == null ? null : internalTx.getStatus().name(),
                record.getDifferenceAmount(),
                record.getReason(),
                record.getReconciledAt()
        );
    }
}
