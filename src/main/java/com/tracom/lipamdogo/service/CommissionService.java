package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.BeneficiaryBalanceResponse;
import com.tracom.lipamdogo.dto.CommissionEntryResponse;
import com.tracom.lipamdogo.dto.CommissionPlanResponse;
import com.tracom.lipamdogo.dto.PartnerCommissionResponse;
import com.tracom.lipamdogo.dto.SettlementLineResponse;
import com.tracom.lipamdogo.model.Agent;
import com.tracom.lipamdogo.model.BeneficiaryType;
import com.tracom.lipamdogo.model.CommissionEntry;
import com.tracom.lipamdogo.model.CommissionStatus;
import com.tracom.lipamdogo.model.CommissionType;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.model.PaymentOrigin;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.repository.AgentRepository;
import com.tracom.lipamdogo.repository.CommissionEntryRepository;
import com.tracom.lipamdogo.repository.SettlementLineRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.support.SystemTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Commission ledger.
 *
 * <p>Repayments belong to M-Simu Finance, which financed the phone. Partners
 * are paid for the business they bring:</p>
 * <ul>
 *   <li><b>Agent</b>: a share of every M-PESA collection they send from their
 *       terminal (capped per payment).</li>
 *   <li><b>Merchant</b>: a smaller override on collections at its terminals,
 *       plus a flat fee for every phone financed from its stock.</li>
 * </ul>
 *
 * <p>Entries accrue when a payment <em>posts</em> (never when it is merely
 * requested) and are clawed back with a negative entry if that payment is
 * later reversed. The ledger is append-only; settlement nets it per partner.</p>
 */
@Service
public class CommissionService {

    private final CommissionEntryRepository entryRepository;
    private final SettlementLineRepository lineRepository;
    private final AgentRepository agentRepository;
    private final MerchantService merchantService;
    private final BigDecimal agentRate;
    private final BigDecimal agentCap;
    private final BigDecimal merchantRate;
    private final BigDecimal merchantSaleFee;

    public CommissionService(CommissionEntryRepository entryRepository,
                             SettlementLineRepository lineRepository,
                             AgentRepository agentRepository,
                             MerchantService merchantService,
                             @Value("${app.commission.agent-collection-rate:0.02}") BigDecimal agentRate,
                             @Value("${app.commission.agent-collection-cap:50}") BigDecimal agentCap,
                             @Value("${app.commission.merchant-collection-rate:0.005}") BigDecimal merchantRate,
                             @Value("${app.commission.merchant-sale-fee:300}") BigDecimal merchantSaleFee) {
        this.entryRepository = entryRepository;
        this.lineRepository = lineRepository;
        this.agentRepository = agentRepository;
        this.merchantService = merchantService;
        this.agentRate = agentRate;
        this.agentCap = agentCap;
        this.merchantRate = merchantRate;
        this.merchantSaleFee = merchantSaleFee;
    }

    public CommissionPlanResponse plan() {
        return new CommissionPlanResponse(agentRate, agentCap, merchantRate, merchantSaleFee);
    }

    // ---- accrual (called inside the posting / reversal / approval transactions) ----

    /** Agent and merchant commission on a posted agent-terminal collection. */
    @Transactional
    public void onPaymentPosted(PaymentTransaction t) {
        if (t.getOrigin() != PaymentOrigin.AGENT_TERMINAL || t.getAgent() == null || t.getTerminal() == null) {
            return;
        }
        if (!entryRepository.findAllByPaymentTransactionId(t.getId()).isEmpty()) {
            return;
        }
        BigDecimal base = t.getPostedAmount() != null ? t.getPostedAmount() : t.getAmount();
        Merchant merchant = t.getTerminal().getMerchant();
        LocalDateTime when = t.getPostedAt() != null ? t.getPostedAt() : SystemTime.now();

        BigDecimal agentAmount = money(base.multiply(agentRate)).min(agentCap);
        accrue(BeneficiaryType.AGENT, t.getAgent(), merchant, CommissionType.AGENT_COLLECTION,
                t, null, base, agentRate, agentAmount, when);
        accrue(BeneficiaryType.MERCHANT, null, merchant, CommissionType.MERCHANT_COLLECTION,
                t, null, base, merchantRate, money(base.multiply(merchantRate)), when);
    }

    /** A reversed payment takes its commissions back (negative entries; nothing is edited). */
    @Transactional
    public void onPaymentReversed(PaymentTransaction t) {
        List<CommissionEntry> entries = entryRepository.findAllByPaymentTransactionId(t.getId());
        Set<Long> alreadyReversed = entries.stream()
                .filter(e -> e.getReversesEntry() != null)
                .map(e -> e.getReversesEntry().getId())
                .collect(Collectors.toSet());
        for (CommissionEntry original : entries) {
            if (original.getReversesEntry() != null || alreadyReversed.contains(original.getId())) {
                continue;
            }
            CommissionEntry clawback = accrue(original.getBeneficiaryType(), original.getAgent(),
                    original.getMerchant(), original.getCommissionType(), t, original.getContract(),
                    original.getBaseAmount(), original.getRate(), original.getAmount().negate(), SystemTime.now());
            if (clawback != null) {
                clawback.setReversesEntry(original);
                entryRepository.save(clawback);
            }
        }
    }

    /** Flat fee to the merchant whose stock the financed phone came from. */
    @Transactional
    public void onContractApproved(LoanContract contract) {
        if (contract.getDevice() == null || contract.getDevice().getMerchant() == null
                || entryRepository.existsByContractIdAndCommissionType(contract.getId(), CommissionType.MERCHANT_SALE)) {
            return;
        }
        accrue(BeneficiaryType.MERCHANT, null, contract.getDevice().getMerchant(), CommissionType.MERCHANT_SALE,
                null, contract, BigDecimal.ONE, null, money(merchantSaleFee), SystemTime.now());
    }

    private CommissionEntry accrue(BeneficiaryType type, Agent agent, Merchant merchant, CommissionType kind,
                                   PaymentTransaction t, LoanContract contract, BigDecimal base, BigDecimal rate,
                                   BigDecimal amount, LocalDateTime when) {
        if (amount.signum() == 0) {
            return null;
        }
        CommissionEntry e = new CommissionEntry();
        e.setBeneficiaryType(type);
        e.setAgent(agent);
        e.setMerchant(merchant);
        e.setCommissionType(kind);
        e.setPaymentTransaction(t);
        e.setContract(contract);
        e.setBaseAmount(base);
        e.setRate(rate);
        e.setAmount(amount);
        e.setStatus(CommissionStatus.ACCRUED);
        e.setEarnedAt(when);
        return entryRepository.save(e);
    }

    // ---- views ----

    @Transactional(readOnly = true)
    public List<CommissionEntryResponse> ledger(int limit) {
        return entryRepository.findAllByOrderByEarnedAtDesc().stream().limit(limit)
                .map(CommissionEntryResponse::from).toList();
    }

    /** Owed / in-settlement / paid per partner, largest balance first. */
    @Transactional(readOnly = true)
    public List<BeneficiaryBalanceResponse> balances() {
        return balancesOf(entryRepository.findAll());
    }

    @Transactional(readOnly = true)
    public PartnerCommissionResponse forAgent(UserPrincipal principal) {
        Agent agent = agentRepository.findByUserAccountId(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent profile not found."));
        List<CommissionEntry> entries = entryRepository.findAllByAgentIdOrderByEarnedAtDesc(agent.getId());
        List<SettlementLineResponse> payouts = lineRepository.findAllByAgentIdOrderByIdDesc(agent.getId()).stream()
                .map(SettlementLineResponse::from).toList();
        return partnerView(entries, payouts, List.of());
    }

    @Transactional(readOnly = true)
    public PartnerCommissionResponse forMerchant(UserPrincipal principal) {
        Merchant merchant = merchantService.requireMerchantForOwner(principal.getId());
        List<CommissionEntry> all = entryRepository.findAllByMerchantIdOrderByEarnedAtDesc(merchant.getId());
        List<CommissionEntry> own = all.stream().filter(e -> e.getBeneficiaryType() == BeneficiaryType.MERCHANT).toList();
        List<BeneficiaryBalanceResponse> agents = balancesOf(
                all.stream().filter(e -> e.getBeneficiaryType() == BeneficiaryType.AGENT).toList());
        // The business's own payouts only; its agents are paid personally (B2C) and shown per agent.
        List<SettlementLineResponse> payouts = lineRepository.findAllByMerchantIdOrderByIdDesc(merchant.getId()).stream()
                .filter(l -> l.getBeneficiaryType() == BeneficiaryType.MERCHANT)
                .map(SettlementLineResponse::from).toList();
        return partnerView(own, payouts, agents);
    }

    /** Commission an agent has earned today (shown on the terminal's end-of-day report). */
    @Transactional(readOnly = true)
    public BigDecimal earnedTodayByAgent(Long agentId) {
        LocalDateTime start = SystemTime.today().atStartOfDay();
        return entryRepository.findAllByAgentIdOrderByEarnedAtDesc(agentId).stream()
                .filter(e -> !e.getEarnedAt().isBefore(start))
                .map(CommissionEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PartnerCommissionResponse partnerView(List<CommissionEntry> entries, List<SettlementLineResponse> payouts,
                                                  List<BeneficiaryBalanceResponse> agents) {
        LocalDateTime start = SystemTime.today().atStartOfDay();
        return new PartnerCommissionResponse(
                plan(),
                sum(entries, CommissionStatus.ACCRUED),
                sum(entries, CommissionStatus.IN_SETTLEMENT),
                sum(entries, CommissionStatus.SETTLED),
                entries.stream().filter(e -> !e.getEarnedAt().isBefore(start))
                        .map(CommissionEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                entries.stream().limit(40).map(CommissionEntryResponse::from).toList(),
                payouts,
                agents);
    }

    private List<BeneficiaryBalanceResponse> balancesOf(Collection<CommissionEntry> entries) {
        Map<String, List<CommissionEntry>> byBeneficiary = new LinkedHashMap<>();
        for (CommissionEntry e : entries) {
            byBeneficiary.computeIfAbsent(key(e), k -> new ArrayList<>()).add(e);
        }
        return byBeneficiary.values().stream().map(list -> {
            CommissionEntry first = list.get(0);
            boolean agent = first.getBeneficiaryType() == BeneficiaryType.AGENT;
            return new BeneficiaryBalanceResponse(
                    first.getBeneficiaryType().name(),
                    agent ? first.getAgent().getId() : null,
                    first.getMerchant().getId(),
                    agent ? first.getAgent().getUserAccount().getFullName() : first.getMerchant().getBusinessName(),
                    first.getMerchant().getBusinessName(),
                    sum(list, CommissionStatus.ACCRUED),
                    sum(list, CommissionStatus.IN_SETTLEMENT),
                    sum(list, CommissionStatus.SETTLED));
        }).sorted((a, b) -> b.accrued().compareTo(a.accrued())).toList();
    }

    static String key(CommissionEntry e) {
        return e.getBeneficiaryType() == BeneficiaryType.AGENT
                ? "AGENT:" + e.getAgent().getId()
                : "MERCHANT:" + e.getMerchant().getId();
    }

    private static BigDecimal sum(Collection<CommissionEntry> entries, CommissionStatus status) {
        return entries.stream().filter(e -> e.getStatus() == status)
                .map(CommissionEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
