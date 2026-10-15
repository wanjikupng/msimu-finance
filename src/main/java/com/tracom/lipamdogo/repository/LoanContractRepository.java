package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LoanContractRepository extends JpaRepository<LoanContract, Long> {

    /**
     * A customer may hold multiple financing contracts. Always use the list
     * variant; never assume a single contract per customer.
     */
    List<LoanContract> findAllByCustomerId(Long customerId);

    /**
     * Fetch one contract while also proving it belongs to the given customer.
     * Used for ownership checks before exposing or mutating contract data.
     */
    Optional<LoanContract> findByIdAndCustomerId(Long id, Long customerId);

    List<LoanContract> findAllByDeviceId(Long deviceId);

    /**
     * Live financing on a device: anything not fully repaid. Used to enforce
     * one active financing contract per physical device.
     */
    @Query("select c from LoanContract c where c.device.id = :deviceId and c.status in :statuses")
    List<LoanContract> findLiveByDeviceId(Long deviceId, Collection<LoanStatus> statuses);

    long countByStatus(LoanStatus status);

    /**
     * Write-locked fetch so concurrent postings against the same contract
     * serialize instead of racing the balance.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LoanContract c where c.id = :id")
    Optional<LoanContract> findByIdForUpdate(Long id);
}
