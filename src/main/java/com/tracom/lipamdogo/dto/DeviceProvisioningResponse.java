package com.tracom.lipamdogo.dto;

import java.time.LocalDate;

/**
 * What a technician loads into a device's lock agent at handover (or into the
 * phone simulator). Contains the device secret: staff-only, never shown to
 * customers.
 */
public record DeviceProvisioningResponse(
        String deviceCode,
        String contractNumber,
        String productName,
        String secretKey,
        LocalDate anchorDate,
        int graceDays,
        UnlockCodeResponse latestCode
) {
}
