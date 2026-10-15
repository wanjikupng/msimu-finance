package com.tracom.lipamdogo.dto;

/**
 * What the terminal app shows after the agent signs in on it.
 */
public record PosSessionResponse(
        String terminalCode,
        String model,
        String merchantName,
        String merchantAddress,
        String agentCode,
        String agentName,
        boolean simulatedCustomerPhone
) {
}
