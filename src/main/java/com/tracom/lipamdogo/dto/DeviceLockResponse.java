package com.tracom.lipamdogo.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Lock status of one financed device and the code that puts it in that state.
 * {@code history} is only filled for staff.
 */
public record DeviceLockResponse(
        Long contractId,
        String contractNumber,
        Long deviceId,
        String deviceCode,
        String lockState,
        LocalDate nextPaymentDueDate,
        LocalDate unlockedUntil,
        int graceDays,
        UnlockCodeResponse latestCode,
        List<UnlockCodeResponse> history
) {
}
