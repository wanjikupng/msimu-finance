package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.CommissionEntry;
import com.tracom.lipamdogo.model.CommissionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface CommissionEntryRepository extends JpaRepository<CommissionEntry, Long> {

    List<CommissionEntry> findAllByPaymentTransactionId(Long paymentTransactionId);

    List<CommissionEntry> findAllBySettlementLineId(Long settlementLineId);

    List<CommissionEntry> findAllByAgentIdOrderByEarnedAtDesc(Long agentId);

    List<CommissionEntry> findAllByMerchantIdOrderByEarnedAtDesc(Long merchantId);

    List<CommissionEntry> findAllByOrderByEarnedAtDesc();

    boolean existsByContractIdAndCommissionType(Long contractId, com.tracom.lipamdogo.model.CommissionType type);

    /** Unpaid entries up to the cut-off, write-locked so two runs cannot settle the same entry. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from CommissionEntry e where e.status = :status and e.earnedAt < :before order by e.id")
    List<CommissionEntry> findForSettlement(CommissionStatus status, LocalDateTime before);
}
