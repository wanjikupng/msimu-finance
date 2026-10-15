package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.ApplicationStatus;
import com.tracom.lipamdogo.model.FinancingApplication;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FinancingApplicationRepository extends JpaRepository<FinancingApplication, Long> {

    List<FinancingApplication> findAllByCustomerId(Long customerId);

    List<FinancingApplication> findAllByCustomerIdAndStatus(Long customerId, ApplicationStatus status);

    List<FinancingApplication> findAllByDeviceId(Long deviceId);

    List<FinancingApplication> findAllByStatus(ApplicationStatus status);

    Optional<FinancingApplication> findByApplicationNumber(String applicationNumber);

    Optional<FinancingApplication> findByIdAndCustomerId(Long id, Long customerId);

    /**
     * Applications that still block the device: anything not terminally
     * closed (REJECTED/CANCELLED).
     */
    @Query("select a from FinancingApplication a where a.device.id = :deviceId and a.status in :statuses")
    List<FinancingApplication> findBlockingByDeviceId(Long deviceId, Collection<ApplicationStatus> statuses);

    /**
     * Write-locked fetch for review/approval decisions so two officers cannot
     * decide the same application concurrently.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FinancingApplication a where a.id = :id")
    Optional<FinancingApplication> findByIdForUpdate(Long id);

    long countByStatus(ApplicationStatus status);
}
