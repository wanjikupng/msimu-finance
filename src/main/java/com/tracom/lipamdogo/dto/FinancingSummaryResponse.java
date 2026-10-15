package com.tracom.lipamdogo.dto;

public record FinancingSummaryResponse(
        long totalApplications,
        long submitted,
        long underReview,
        long approved,
        long rejected,
        long cancelled,
        long activeContracts
) {
}
