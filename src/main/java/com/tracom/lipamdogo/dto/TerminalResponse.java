package com.tracom.lipamdogo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Terminal as seen by the terminal management console, with today's
 * M-PESA collection figures.
 */
public record TerminalResponse(
        Long id,
        String terminalCode,
        String serialNumber,
        String model,
        Long merchantId,
        String merchantName,
        Long agentId,
        String agentCode,
        String agentName,
        String status,
        String appVersion,
        LocalDateTime lastSeenAt,
        boolean online,
        long todayApprovedCount,
        BigDecimal todayApprovedAmount,
        long todayDeclinedCount,
        LocalDateTime createdAt
) {
}
