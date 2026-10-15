package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.ApplicationStatus;
import com.tracom.lipamdogo.model.FinancingApplication;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Business projection of a financing application. Carries display data the
 * customer and officer need, and nothing else: no passwords, no account
 * objects, no persistence internals.
 */
public record FinancingApplicationResponse(
        Long id,
        String applicationNumber,
        ApplicationStatus status,
        Long customerId,
        String customerCode,
        String customerName,
        Long deviceId,
        String deviceCode,
        String imeiMasked,
        String productName,
        BigDecimal devicePrice,
        BigDecimal depositAmount,
        BigDecimal requestedPrincipal,
        BigDecimal installmentAmount,
        BigDecimal approvedPrincipal,
        Long paymentPlanId,
        String paymentPlanName,
        String paymentMode,
        String reviewedByName,
        String decisionReason,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        LocalDateTime updatedAt
) {
    public static FinancingApplicationResponse from(FinancingApplication application) {
        return new FinancingApplicationResponse(
                application.getId(),
                application.getApplicationNumber(),
                application.getStatus(),
                application.getCustomer().getId(),
                application.getCustomer().getCustomerCode(),
                application.getCustomer().getFullName(),
                application.getDevice().getId(),
                application.getDevice().getDeviceCode(),
                maskImei(application.getDevice().getImei()),
                application.getDevice().getProduct().getName(),
                application.getDevicePrice(),
                application.getDepositAmount(),
                application.getRequestedPrincipal(),
                application.getInstallmentAmount(),
                application.getApprovedPrincipal(),
                application.getPaymentPlan().getId(),
                application.getPaymentPlan().getPlanName(),
                application.getPaymentMode() == null ? null : application.getPaymentMode().getModeName(),
                application.getReviewedBy() == null ? null : application.getReviewedBy().getFullName(),
                application.getDecisionReason(),
                application.getSubmittedAt(),
                application.getReviewedAt(),
                application.getUpdatedAt()
        );
    }

    private static String maskImei(String imei) {
        if (imei == null || imei.length() < 4) {
            return null;
        }
        return "************" + imei.substring(imei.length() - 4);
    }
}
