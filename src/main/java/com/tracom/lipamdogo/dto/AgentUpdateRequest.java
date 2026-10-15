package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.AgentStatus;

public record AgentUpdateRequest(
        Long merchantId,
        AgentStatus status
) {
}
