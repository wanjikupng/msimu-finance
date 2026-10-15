package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.AllocationStatus;
import com.tracom.lipamdogo.model.PaymentAllocation;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentAllocationResponse(
        Long id,
        LocalDate allocationDate,
        BigDecimal amount,
        Integer sequence,
        AllocationStatus status
) {
    public static PaymentAllocationResponse from(PaymentAllocation allocation) {
        return new PaymentAllocationResponse(
                allocation.getId(),
                allocation.getAllocationDate(),
                allocation.getAmount(),
                allocation.getSequence(),
                allocation.getStatus()
        );
    }
}
