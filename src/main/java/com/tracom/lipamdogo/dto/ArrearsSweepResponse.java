package com.tracom.lipamdogo.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Outcome of one end-of-day arrears sweep.
 */
public record ArrearsSweepResponse(
        LocalDate businessDate,
        int contractsChecked,
        int newlyOverdue,
        int backToActive,
        int overdueTotal,
        LocalDateTime ranAt
) {
}
