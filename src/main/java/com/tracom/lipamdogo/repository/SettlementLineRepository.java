package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.SettlementLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementLineRepository extends JpaRepository<SettlementLine, Long> {

    List<SettlementLine> findAllByBatchIdOrderByIdAsc(Long batchId);

    List<SettlementLine> findAllByAgentIdOrderByIdDesc(Long agentId);

    List<SettlementLine> findAllByMerchantIdOrderByIdDesc(Long merchantId);
}
