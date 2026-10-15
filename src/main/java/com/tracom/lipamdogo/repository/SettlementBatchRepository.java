package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.SettlementBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {

    List<SettlementBatch> findAllByOrderByIdDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SettlementBatch b where b.id = :id")
    Optional<SettlementBatch> findByIdForUpdate(Long id);
}
