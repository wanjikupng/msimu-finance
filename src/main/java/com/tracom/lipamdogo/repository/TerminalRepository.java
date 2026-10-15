package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.Terminal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TerminalRepository extends JpaRepository<Terminal, Long> {

    Optional<Terminal> findByTerminalCode(String terminalCode);

    boolean existsBySerialNumber(String serialNumber);

    List<Terminal> findAllByOrderByTerminalCodeAsc();

    List<Terminal> findAllByMerchantIdOrderByTerminalCodeAsc(Long merchantId);

    List<Terminal> findAllByAgentIdOrderByTerminalCodeAsc(Long agentId);
}
