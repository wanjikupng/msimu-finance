package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.UnlockCode;
import com.tracom.lipamdogo.security.UnlockCodeCodec;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record UnlockCodeResponse(
        int sequence,
        String code,
        LocalDate validUntil,
        boolean permanent,
        String reason,
        String paymentReference,
        LocalDateTime issuedAt
) {
    public static UnlockCodeResponse from(UnlockCode code) {
        return new UnlockCodeResponse(
                code.getSequence(),
                UnlockCodeCodec.format(code.getCode()),
                code.getValidUntil(),
                code.isPermanent(),
                code.getReason().name(),
                code.getPaymentTransaction() == null ? null : code.getPaymentTransaction().getTransactionReference(),
                code.getIssuedAt());
    }
}
