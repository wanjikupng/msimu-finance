package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.model.MerchantStatus;

import java.time.LocalDateTime;

public record MerchantResponse(
        Long id,
        String merchantCode,
        String businessName,
        String registrationNumber,
        String phone,
        String email,
        String address,
        MerchantStatus status,
        Long ownerId,
        String ownerName,
        long agentCount,
        long deviceCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static MerchantResponse from(Merchant merchant, long agentCount, long deviceCount) {
        return new MerchantResponse(
                merchant.getId(),
                merchant.getMerchantCode(),
                merchant.getBusinessName(),
                merchant.getRegistrationNumber(),
                merchant.getPhone(),
                merchant.getEmail(),
                merchant.getAddress(),
                merchant.getStatus(),
                merchant.getOwner() == null ? null : merchant.getOwner().getId(),
                merchant.getOwner() == null ? null : merchant.getOwner().getFullName(),
                agentCount,
                deviceCount,
                merchant.getCreatedAt(),
                merchant.getUpdatedAt()
        );
    }
}
