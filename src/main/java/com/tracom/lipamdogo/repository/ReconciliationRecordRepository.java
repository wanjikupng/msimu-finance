package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.ProviderTransaction;
import com.tracom.lipamdogo.model.ReconciliationRecord;
import com.tracom.lipamdogo.model.ReconciliationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconciliationRecordRepository extends JpaRepository<ReconciliationRecord, Long> {

    List<ReconciliationRecord> findByBatchId(Long batchId);

    List<ReconciliationRecord> findByStatus(ReconciliationStatus status);

    List<ReconciliationRecord> findByBatchIdAndStatus(Long batchId, ReconciliationStatus status);

    Optional<ReconciliationRecord> findFirstByPaymentTransactionOrderByIdDesc(PaymentTransaction paymentTransaction);

    Optional<ReconciliationRecord> findFirstByProviderTransactionOrderByIdDesc(ProviderTransaction providerTransaction);

    Optional<ReconciliationRecord> findFirstByPaymentTransactionAndProviderTransactionOrderByIdDesc(
            PaymentTransaction paymentTransaction, ProviderTransaction providerTransaction);
}
