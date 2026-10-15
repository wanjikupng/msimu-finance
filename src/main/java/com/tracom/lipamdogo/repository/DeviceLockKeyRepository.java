package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.DeviceLockKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface DeviceLockKeyRepository extends JpaRepository<DeviceLockKey, Long> {

    Optional<DeviceLockKey> findByContractId(Long contractId);

    /** Latest key for a device (a device is financed at most once at a time). */
    Optional<DeviceLockKey> findFirstByDeviceIdOrderByIdDesc(Long deviceId);

    /** Write-locked so two postings cannot hand out the same sequence number. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from DeviceLockKey k where k.contract.id = :contractId")
    Optional<DeviceLockKey> findByContractIdForUpdate(Long contractId);
}
