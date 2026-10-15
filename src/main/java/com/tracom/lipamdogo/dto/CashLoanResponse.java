package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.CashLoan;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CashLoanResponse(
        Long id,
        Long customerId,
        String customerCode,
        String customerName,
        BigDecimal amountRequested,
        BigDecimal approvedAmount,
        BigDecimal balanceRemaining,
        BigDecimal monthlyRate,
        String purpose,
        String status,
        LocalDateTime createdAt
) {
    public static CashLoanResponse from(CashLoan loan) {
        return new CashLoanResponse(
                loan.getId(),
                loan.getCustomer().getId(),
                loan.getCustomer().getCustomerCode(),
                loan.getCustomer().getFullName(),
                loan.getAmountRequested(),
                loan.getApprovedAmount(),
                loan.getBalanceRemaining(),
                loan.getMonthlyRate(),
                loan.getPurpose(),
                loan.getStatus(),
                loan.getCreatedAt()
        );
    }
}
