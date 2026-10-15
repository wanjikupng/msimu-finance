package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.PaymentTransactionResponse;
import com.tracom.lipamdogo.dto.TerminalAssignRequest;
import com.tracom.lipamdogo.dto.TerminalRequest;
import com.tracom.lipamdogo.dto.TerminalResponse;
import com.tracom.lipamdogo.dto.TerminalStatusRequest;
import com.tracom.lipamdogo.model.Agent;
import com.tracom.lipamdogo.model.AgentStatus;
import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
import com.tracom.lipamdogo.model.Terminal;
import com.tracom.lipamdogo.model.TerminalStatus;
import com.tracom.lipamdogo.repository.AgentRepository;
import com.tracom.lipamdogo.repository.MerchantRepository;
import com.tracom.lipamdogo.repository.PaymentReceiptRepository;
import com.tracom.lipamdogo.repository.PaymentTransactionRepository;
import com.tracom.lipamdogo.repository.TerminalRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.support.SystemTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Terminal management (TMS): registering POS terminals at merchants,
 * assigning them to agents, taking them out of service, and watching whether
 * they are online and what they collected today.
 */
@Service
public class TerminalService {

    /** A terminal that has not sent a heartbeat for this long shows offline. */
    static final long ONLINE_WINDOW_MINUTES = 5;

    private final TerminalRepository terminalRepository;
    private final MerchantRepository merchantRepository;
    private final AgentRepository agentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentReceiptRepository receiptRepository;

    public TerminalService(TerminalRepository terminalRepository,
                           MerchantRepository merchantRepository,
                           AgentRepository agentRepository,
                           PaymentTransactionRepository transactionRepository,
                           PaymentReceiptRepository receiptRepository) {
        this.terminalRepository = terminalRepository;
        this.merchantRepository = merchantRepository;
        this.agentRepository = agentRepository;
        this.transactionRepository = transactionRepository;
        this.receiptRepository = receiptRepository;
    }

    @Transactional
    public TerminalResponse register(TerminalRequest request) {
        String serial = request.serialNumber().trim().toUpperCase();
        if (terminalRepository.existsBySerialNumber(serial)) {
            throw new IllegalArgumentException("A terminal with serial number " + serial + " is already registered.");
        }
        Merchant merchant = merchantRepository.findById(request.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant not found."));
        Terminal terminal = new Terminal();
        terminal.setSerialNumber(serial);
        terminal.setModel(request.model().trim());
        terminal.setMerchant(merchant);
        terminal.setStatus(TerminalStatus.ACTIVE);
        terminal.setTerminalCode("TMP-" + serial);
        Terminal saved = terminalRepository.saveAndFlush(terminal);
        saved.setTerminalCode("TRM-%04d".formatted(saved.getId()));
        return toResponse(terminalRepository.save(saved));
    }

    /** An agent may only operate terminals deployed at their own merchant. */
    @Transactional
    public TerminalResponse assign(Long terminalId, TerminalAssignRequest request) {
        Terminal terminal = require(terminalId);
        if (terminal.getStatus() == TerminalStatus.RETIRED) {
            throw new IllegalArgumentException("A retired terminal cannot be assigned.");
        }
        Agent agent = agentRepository.findById(request.agentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found."));
        if (!agent.getMerchant().getId().equals(terminal.getMerchant().getId())) {
            throw new IllegalArgumentException("Agent " + agent.getAgentCode() + " works for "
                    + agent.getMerchant().getBusinessName() + ", but this terminal is deployed at "
                    + terminal.getMerchant().getBusinessName() + ".");
        }
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new IllegalArgumentException("Only ACTIVE agents can be given a terminal.");
        }
        terminal.setAgent(agent);
        terminal.setUpdatedAt(SystemTime.now());
        return toResponse(terminalRepository.save(terminal));
    }

    @Transactional
    public TerminalResponse unassign(Long terminalId) {
        Terminal terminal = require(terminalId);
        terminal.setAgent(null);
        terminal.setUpdatedAt(SystemTime.now());
        return toResponse(terminalRepository.save(terminal));
    }

    @Transactional
    public TerminalResponse changeStatus(Long terminalId, TerminalStatusRequest request) {
        Terminal terminal = require(terminalId);
        if (terminal.getStatus() == TerminalStatus.RETIRED) {
            throw new IllegalArgumentException("Retired terminals cannot change status.");
        }
        terminal.setStatus(request.status());
        if (request.status() == TerminalStatus.RETIRED) {
            terminal.setAgent(null);
        }
        terminal.setUpdatedAt(SystemTime.now());
        return toResponse(terminalRepository.save(terminal));
    }

    @Transactional(readOnly = true)
    public List<TerminalResponse> list() {
        return terminalRepository.findAllByOrderByTerminalCodeAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<TerminalResponse> forMerchant(Long merchantId) {
        return terminalRepository.findAllByMerchantIdOrderByTerminalCodeAsc(merchantId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<TerminalResponse> forAgentUser(UserPrincipal principal) {
        Agent agent = agentRepository.findByUserAccountId(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent profile not found."));
        return terminalRepository.findAllByAgentIdOrderByTerminalCodeAsc(agent.getId()).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public TerminalResponse get(Long terminalId) {
        return toResponse(require(terminalId));
    }

    @Transactional(readOnly = true)
    public List<PaymentTransactionResponse> recentTransactions(Long terminalId) {
        require(terminalId);
        return transactionRepository.findAllByTerminalIdOrderByRequestedAtDesc(terminalId).stream()
                .limit(50)
                .map(this::toTransactionResponse)
                .toList();
    }

    PaymentTransactionResponse toTransactionResponse(PaymentTransaction t) {
        String receipt = t.getStatus() == PaymentTransactionStatus.POSTED
                ? receiptRepository.findByTransactionId(t.getId()).map(r -> r.getReceiptNumber()).orElse(null)
                : null;
        return PaymentTransactionResponse.from(t, receipt);
    }

    TerminalResponse toResponse(Terminal terminal) {
        LocalDateTime dayStart = SystemTime.today().atStartOfDay();
        List<PaymentTransaction> today = terminal.getId() == null ? List.of()
                : transactionRepository.findAllByTerminalIdAndRequestedAtBetweenOrderByRequestedAtDesc(
                        terminal.getId(), dayStart, dayStart.plusDays(1));
        long approved = today.stream().filter(t -> t.getStatus() == PaymentTransactionStatus.POSTED).count();
        BigDecimal approvedAmount = today.stream()
                .filter(t -> t.getStatus() == PaymentTransactionStatus.POSTED)
                .map(PaymentTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long declined = today.stream().filter(t -> t.getStatus() == PaymentTransactionStatus.FAILED).count();
        Agent agent = terminal.getAgent();
        return new TerminalResponse(
                terminal.getId(),
                terminal.getTerminalCode(),
                terminal.getSerialNumber(),
                terminal.getModel(),
                terminal.getMerchant().getId(),
                terminal.getMerchant().getBusinessName(),
                agent == null ? null : agent.getId(),
                agent == null ? null : agent.getAgentCode(),
                agent == null ? null : agent.getUserAccount().getFullName(),
                terminal.getStatus().name(),
                terminal.getAppVersion(),
                terminal.getLastSeenAt(),
                isOnline(terminal),
                approved,
                approvedAmount,
                declined,
                terminal.getCreatedAt());
    }

    static boolean isOnline(Terminal terminal) {
        return terminal.getLastSeenAt() != null
                && terminal.getLastSeenAt().isAfter(SystemTime.now().minusMinutes(ONLINE_WINDOW_MINUTES));
    }

    private Terminal require(Long terminalId) {
        return terminalRepository.findById(terminalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Terminal not found."));
    }
}
