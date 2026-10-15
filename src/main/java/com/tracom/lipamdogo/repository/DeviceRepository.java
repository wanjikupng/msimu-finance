package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.Device;
import com.tracom.lipamdogo.model.DeviceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByDeviceCode(String deviceCode);

    boolean existsByDeviceCode(String deviceCode);

    boolean existsByImei(String imei);

    boolean existsBySerialNumber(String serialNumber);

    List<Device> findAllByStatus(DeviceStatus status);

    List<Device> findAllByProductId(Long productId);

    List<Device> findAllByMerchantId(Long merchantId);

    List<Device> findAllByMerchantIdAndStatus(Long merchantId, DeviceStatus status);

    List<Device> findAllByAgentId(Long agentId);

    List<Device> findAllByCustomerId(Long customerId);

    Optional<Device> findByIdAndCustomerId(Long id, Long customerId);

    Optional<Device> findByIdAndAgentId(Long id, Long agentId);

    Optional<Device> findByIdAndMerchantId(Long id, Long merchantId);

    long countByMerchantId(Long merchantId);

    long countByAgentId(Long agentId);

    /**
     * Write-locked fetch so concurrent financing operations on the same
     * physical device serialize instead of double-committing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    Optional<Device> findByIdForUpdate(Long id);
}
