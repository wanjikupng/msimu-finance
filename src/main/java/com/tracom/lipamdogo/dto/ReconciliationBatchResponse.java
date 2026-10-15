package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.ReconciliationBatch;
import com.tracom.lipamdogo.model.ReconciliationBatchStatus;

import java.time.LocalDateTime;

public record ReconciliationBatchResponse(
        Long id,
        String batchNumber,
        PaymentChannel provider,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        ReconciliationBatchStatus status,
        long totalRecords,
        long matchedCount,
        long exceptionCount,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public static ReconciliationBatchResponse from(ReconciliationBatch batch) {
        return new ReconciliationBatchResponse(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getProvider(),
                batch.getWindowStart(),
                batch.getWindowEnd(),
                batch.getStatus(),
                batch.getTotalRecords(),
                batch.getMatchedCount(),
                batch.getExceptionCount(),
                batch.getStartedAt(),
                batch.getCompletedAt()
        );
    }
}
