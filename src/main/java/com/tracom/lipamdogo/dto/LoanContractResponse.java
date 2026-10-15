package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.PaymentFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Public projection of a financing contract. Customer identity is exposed as
 * the stable database id plus display name/code, never as a free-text join key.
 * Approved terms are a snapshot taken at approval time (never live catalogue data).
 */
public record LoanContractResponse(
        Long id,
        String contractNumber,
        Long customerId,
        String customerCode,
        String customerName,
        Long productId,
        String productName,
        Long deviceId,
        String deviceCode,
        Long applicationId,
        String applicationNumber,
        BigDecimal depositPaid,
        BigDecimal amountRemaining,
        BigDecimal amountPaid,
        BigDecimal dailyAmount,
        BigDecimal principalAmount,
        BigDecimal totalPayable,
        BigDecimal devicePrice,
        PaymentFrequency paymentFrequency,
        String planName,
        Integer planDurationDays,
        String loanStatus,
        String deviceStatus,
        LocalDate nextPaymentDueDate,
        LocalDateTime createdAt
) {
    public static LoanContractResponse from(LoanContract contract) {
        return new LoanContractResponse(
                contract.getId(),
                contract.getContractNumber(),
                contract.getCustomer().getId(),
                contract.getCustomer().getCustomerCode(),
                contract.getCustomer().getFullName(),
                contract.getProduct().getId(),
                contract.getProduct().getName(),
                contract.getDevice() == null ? null : contract.getDevice().getId(),
                contract.getDevice() == null ? null : contract.getDevice().getDeviceCode(),
                contract.getApplication() == null ? null : contract.getApplication().getId(),
                contract.getApplication() == null ? null : contract.getApplication().getApplicationNumber(),
                contract.getDepositPaid(),
                contract.getAmountRemaining(),
                contract.getAmountPaid(),
                contract.getDailyAmount(),
                contract.getPrincipalAmount(),
                contract.getTotalPayable(),
                contract.getDevicePrice(),
                contract.getPaymentFrequency(),
                contract.getPlanName(),
                contract.getPlanDurationDays(),
                contract.getStatus().name(),
                contract.getDevice() == null ? null : contract.getDevice().getStatus().name(),
                contract.getNextPaymentDueDate(),
                contract.getCreatedAt()
        );
    }
}
