package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.PaymentReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, Long> {

    Optional<PaymentReceipt> findByReceiptNumber(String receiptNumber);

    Optional<PaymentReceipt> findByTransactionId(Long transactionId);

    List<PaymentReceipt> findAllByTransactionCustomerId(Long customerId);
}
