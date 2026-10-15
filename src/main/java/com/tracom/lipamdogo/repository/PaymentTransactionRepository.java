package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByTransactionReference(String transactionReference);

    Optional<PaymentTransaction> findByProviderReference(String providerReference);

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    List<PaymentTransaction> findAllByCustomerId(Long customerId);

    List<PaymentTransaction> findAllByCustomerIdAndStatus(Long customerId, PaymentTransactionStatus status);

    List<PaymentTransaction> findAllByContractId(Long contractId);

    List<PaymentTransaction> findAllByStatus(PaymentTransactionStatus status);

    List<PaymentTransaction> findAllByTerminalIdOrderByRequestedAtDesc(Long terminalId);

    List<PaymentTransaction> findAllByTerminalIdAndRequestedAtBetweenOrderByRequestedAtDesc(
            Long terminalId, java.time.LocalDateTime from, java.time.LocalDateTime to);

    List<PaymentTransaction> findAllByAgentIdAndRequestedAtBetweenOrderByRequestedAtDesc(
            Long agentId, java.time.LocalDateTime from, java.time.LocalDateTime to);

    List<PaymentTransaction> findAllByContractIdAndStatus(Long contractId, PaymentTransactionStatus status);

    /**
     * Write-locked fetch so concurrent postings/callbacks on the same
     * transaction serialize instead of double-applying.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.transactionReference = :reference")
    Optional<PaymentTransaction> findByReferenceForUpdate(String reference);

    /**
     * Total amount currently reserved against a contract by transactions that
     * may still become financially effective. Derived live from transaction
     * rows (no duplicated reservation column); served by idx_payment_tx_contract.
     */
    @Query("select coalesce(sum(t.amount), 0) from PaymentTransaction t where t.contract.id = :contractId and t.status in :statuses")
    BigDecimal sumReservedByContractId(Long contractId, Collection<PaymentTransactionStatus> statuses);
}
