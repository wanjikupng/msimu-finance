package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.Device;
import com.tracom.lipamdogo.model.DeviceStatus;

import java.time.LocalDateTime;

public record DeviceResponse(
        Long id,
        String deviceCode,
        String imei,
        String serialNumber,
        Long productId,
        String productName,
        DeviceStatus status,
        Long merchantId,
        String merchantCode,
        String merchantName,
        Long agentId,
        String agentCode,
        String agentName,
        Long customerId,
        String customerCode,
        String customerName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static DeviceResponse from(Device device) {
        return new DeviceResponse(
                device.getId(),
                device.getDeviceCode(),
                device.getImei(),
                device.getSerialNumber(),
                device.getProduct().getId(),
                device.getProduct().getName(),
                device.getStatus(),
                device.getMerchant() == null ? null : device.getMerchant().getId(),
                device.getMerchant() == null ? null : device.getMerchant().getMerchantCode(),
                device.getMerchant() == null ? null : device.getMerchant().getBusinessName(),
                device.getAgent() == null ? null : device.getAgent().getId(),
                device.getAgent() == null ? null : device.getAgent().getAgentCode(),
                device.getAgent() == null ? null : device.getAgent().getUserAccount().getFullName(),
                device.getCustomer() == null ? null : device.getCustomer().getId(),
                device.getCustomer() == null ? null : device.getCustomer().getCustomerCode(),
                device.getCustomer() == null ? null : device.getCustomer().getFullName(),
                device.getCreatedAt(),
                device.getUpdatedAt()
        );
    }
}
