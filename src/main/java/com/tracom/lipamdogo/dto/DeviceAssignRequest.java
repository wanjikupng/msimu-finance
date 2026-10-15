package com.tracom.lipamdogo.dto;

/**
 * Exactly one target may be set: assigning to a customer takes precedence and
 * requires the device to already sit with a merchant/agent channel.
 */
public record DeviceAssignRequest(
        Long merchantId,
        Long agentId,
        Long customerId
) {
}
