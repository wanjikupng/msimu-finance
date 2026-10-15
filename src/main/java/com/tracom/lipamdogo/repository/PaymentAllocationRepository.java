package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.AllocationStatus;
import com.tracom.lipamdogo.model.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    List<PaymentAllocation> findByPaymentTransactionId(Long paymentTransactionId);

    List<PaymentAllocation> findByPaymentTransactionIdAndStatus(Long paymentTransactionId, AllocationStatus status);

    List<PaymentAllocation> findByContractId(Long contractId);

    List<PaymentAllocation> findAllByContractIdAndStatusOrderByIdAsc(Long contractId, AllocationStatus status);
}
