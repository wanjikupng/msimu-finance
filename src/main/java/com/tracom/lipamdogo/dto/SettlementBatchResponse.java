package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SettlementBatchResponse(
        Long id,
        String batchNumber,
        LocalDate cutoffDate,
        String status,
        int lineCount,
        BigDecimal totalAmount,
        Long createdById,
        String createdBy,
        String decidedBy,
        String decisionNote,
        LocalDateTime createdAt,
        LocalDateTime decidedAt,
        List<SettlementLineResponse> lines
) {
}
