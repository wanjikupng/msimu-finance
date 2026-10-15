package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.AgentRequest;
import com.tracom.lipamdogo.dto.AgentResponse;
import com.tracom.lipamdogo.dto.AgentUpdateRequest;
import com.tracom.lipamdogo.model.Agent;
import com.tracom.lipamdogo.model.AgentStatus;
import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.model.MerchantStatus;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.AgentRepository;
import com.tracom.lipamdogo.repository.MerchantRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AgentService {

    private final AgentRepository agentRepository;
    private final MerchantRepository merchantRepository;
    private final UserAccountRepository userAccountRepository;

    public AgentService(AgentRepository agentRepository,
                        MerchantRepository merchantRepository,
                        UserAccountRepository userAccountRepository) {
        this.agentRepository = agentRepository;
        this.merchantRepository = merchantRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional
    public AgentResponse createAgent(AgentRequest request) {
        UserAccount account = userAccountRepository.findById(request.userAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found."));
        if (account.getRole() != Role.AGENT) {
            throw new IllegalArgumentException("Agent profile requires an account with the AGENT role.");
        }
        if (agentRepository.existsByUserAccountId(account.getId())) {
            throw new IllegalArgumentException("This account already has an agent profile.");
        }
        Merchant merchant = merchantRepository.findById(request.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant not found."));
        if (merchant.getStatus() == MerchantStatus.CLOSED) {
            throw new IllegalArgumentException("Agents cannot be attached to a closed merchant.");
        }

        Agent agent = new Agent();
        agent.setAgentCode(generateAgentCode());
        agent.setUserAccount(account);
        agent.setMerchant(merchant);
        agent.setStatus(AgentStatus.PENDING);
        return AgentResponse.from(agentRepository.save(agent));
    }

    @Transactional
    public AgentResponse updateAgent(Long id, AgentUpdateRequest request) {
        Agent agent = requireAgent(id);
        if (request.merchantId() != null) {
            Merchant merchant = merchantRepository.findById(request.merchantId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant not found."));
            if (merchant.getStatus() == MerchantStatus.CLOSED) {
                throw new IllegalArgumentException("Agents cannot be attached to a closed merchant.");
            }
            agent.setMerchant(merchant);
        }
        if (request.status() != null) {
            agent.setStatus(request.status());
        }
        agent.setUpdatedAt(LocalDateTime.now());
        return AgentResponse.from(agentRepository.save(agent));
    }

    @Transactional(readOnly = true)
    public List<AgentResponse> listAgents(Long merchantId) {
        List<Agent> agents = merchantId == null
                ? agentRepository.findAll()
                : agentRepository.findAllByMerchantId(merchantId);
        return agents.stream().map(AgentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AgentResponse getAgent(Long id) {
        return AgentResponse.from(requireAgent(id));
    }

    @Transactional(readOnly = true)
    public Agent requireAgent(Long id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found."));
    }

    @Transactional(readOnly = true)
    public AgentResponse profileForAccount(Long userAccountId) {
        Agent agent = agentRepository.findByUserAccountId(userAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent profile not found."));
        return AgentResponse.from(agent);
    }

    @Transactional(readOnly = true)
    public Agent requireAgentForAccount(Long userAccountId) {
        return agentRepository.findByUserAccountId(userAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent profile not found."));
    }

    private String generateAgentCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "AGT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            if (!agentRepository.existsByAgentCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique agent code.");
    }
}
