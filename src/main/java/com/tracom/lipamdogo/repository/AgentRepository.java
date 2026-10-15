package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    Optional<Agent> findByAgentCode(String agentCode);

    boolean existsByAgentCode(String agentCode);

    Optional<Agent> findByUserAccountId(Long userAccountId);

    boolean existsByUserAccountId(Long userAccountId);

    List<Agent> findAllByMerchantId(Long merchantId);

    long countByMerchantId(Long merchantId);
}
