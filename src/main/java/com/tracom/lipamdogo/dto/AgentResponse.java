package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.Agent;
import com.tracom.lipamdogo.model.AgentStatus;

import java.time.LocalDateTime;

public record AgentResponse(
        Long id,
        String agentCode,
        AgentStatus status,
        Long userAccountId,
        String fullName,
        String email,
        String phoneNumber,
        Long merchantId,
        String merchantCode,
        String merchantName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentResponse from(Agent agent) {
        return new AgentResponse(
                agent.getId(),
                agent.getAgentCode(),
                agent.getStatus(),
                agent.getUserAccount().getId(),
                agent.getUserAccount().getFullName(),
                agent.getUserAccount().getEmail(),
                agent.getUserAccount().getPhoneNumber(),
                agent.getMerchant().getId(),
                agent.getMerchant().getMerchantCode(),
                agent.getMerchant().getBusinessName(),
                agent.getCreatedAt(),
                agent.getUpdatedAt()
        );
    }
}
