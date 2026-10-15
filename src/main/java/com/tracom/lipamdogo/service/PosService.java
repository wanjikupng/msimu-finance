package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.PaymentTransactionResponse;
import com.tracom.lipamdogo.dto.PosCollectRequest;
import com.tracom.lipamdogo.dto.PosCustomerResponse;
import com.tracom.lipamdogo.dto.PosDaySummaryResponse;
import com.tracom.lipamdogo.dto.PosReceiptResponse;
import com.tracom.lipamdogo.dto.PosSessionResponse;
import com.tracom.lipamdogo.dto.UnlockCodeResponse;
import com.tracom.lipamdogo.model.Agent;
import com.tracom.lipamdogo.model.AgentStatus;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.model.MerchantStatus;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.Terminal;
import com.tracom.lipamdogo.model.TerminalStatus;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.AgentRepository;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentReceiptRepository;
import com.tracom.lipamdogo.repository.PaymentTransactionRepository;
import com.tracom.lipamdogo.repository.TerminalRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.support.SystemTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * What an agent does on a POS terminal: find the customer, send an M-PESA
 * payment prompt to their phone, print the receipt (with the new unlock code),
 * and run the end-of-day report.
 *
 * <p>Cashless by design. The agent never takes money: the customer approves
 * the payment on their own phone with their M-PESA PIN, and the funds land in
 * the business's M-PESA account. Every call is checked against the terminal:
 * it must be ACTIVE, assigned to the signed-in agent, and both the agent and
 * their merchant must be ACTIVE.</p>
 */
@Service
public class PosService {

    private final TerminalRepository terminalRepository;
    private final AgentRepository agentRepository;
    private final UserAccountRepository userAccountRepository;
    private final LoanContractRepository contractRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentReceiptRepository receiptRepository;
    private final PaymentService paymentService;
    private final DeviceLockService deviceLockService;
    private final TerminalService terminalService;
    private final boolean simulatedCustomerPhone;

    public PosService(TerminalRepository terminalRepository,
                      AgentRepository agentRepository,
                      UserAccountRepository userAccountRepository,
                      LoanContractRepository contractRepository,
                      PaymentTransactionRepository transactionRepository,
                      PaymentReceiptRepository receiptRepository,
                      PaymentService paymentService,
                      DeviceLockService deviceLockService,
                      TerminalService terminalService,
                      @Value("${app.payments.simulated-customer-phone:true}") boolean simulatedCustomerPhone) {
        this.terminalRepository = terminalRepository;
        this.agentRepository = agentRepository;
        this.userAccountRepository = userAccountRepository;
        this.contractRepository = contractRepository;
        this.transactionRepository = transactionRepository;
        this.receiptRepository = receiptRepository;
        this.paymentService = paymentService;
        this.deviceLockService = deviceLockService;
        this.terminalService = terminalService;
        this.simulatedCustomerPhone = simulatedCustomerPhone;
    }

    /** Signing in on the terminal doubles as a heartbeat. */
    @Transactional
    public PosSessionResponse session(String terminalCode, UserPrincipal principal, String appVersion) {
        Terminal terminal = heartbeat(terminalCode, principal, appVersion);
        Agent agent = terminal.getAgent();
        return new PosSessionResponse(
                terminal.getTerminalCode(),
                terminal.getModel(),
                terminal.getMerchant().getBusinessName(),
                terminal.getMerchant().getAddress(),
                agent.getAgentCode(),
                agent.getUserAccount().getFullName(),
                simulatedCustomerPhone);
    }

    @Transactional
    public Terminal heartbeat(String terminalCode, UserPrincipal principal, String appVersion) {
        Terminal terminal = requireOperable(terminalCode, principal);
        terminal.setLastSeenAt(SystemTime.now());
        if (appVersion != null && !appVersion.isBlank()) {
            terminal.setAppVersion(appVersion.trim());
        }
        return terminalRepository.save(terminal);
    }

    /** Find a customer by M-PESA phone number, contract number or customer code. */
    @Transactional(readOnly = true)
    public PosCustomerResponse lookup(String terminalCode, UserPrincipal principal, String query) {
        requireOperable(terminalCode, principal);
        String q = query == null ? "" : query.trim();
        if (q.length() < 4) {
            throw new IllegalArgumentException("Enter a phone number, contract number or customer code.");
        }
        UserAccount customer = findCustomer(q)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No customer matches " + q + "."));
        List<PosCustomerResponse.Contract> contracts = contractRepository.findAllByCustomerId(customer.getId()).stream()
                .filter(c -> c.getStatus() == LoanStatus.ACTIVE || c.getStatus() == LoanStatus.OVERDUE)
                .map(this::toPosContract)
                .toList();
        return new PosCustomerResponse(displayName(customer.getFullName()),
                PaymentTransactionResponse.maskMsisdn(normalizeMsisdn(customer.getPhoneNumber())), contracts);
    }

    /** Sends the M-PESA prompt to the customer's phone. The payment stays PENDING until they respond. */
    @Transactional
    public PaymentTransactionResponse collect(String terminalCode, UserPrincipal principal,
                                              PosCollectRequest request, String idempotencyKey) {
        Terminal terminal = requireOperable(terminalCode, principal);
        LoanContract contract = contractRepository.findById(request.contractId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Financing contract not found."));
        if (contract.getCustomer().getRole() != Role.CUSTOMER) {
            throw new IllegalArgumentException("Payments can only be collected for customers.");
        }
        String payer = request.payerMsisdn() == null || request.payerMsisdn().isBlank()
                ? normalizeMsisdn(contract.getCustomer().getPhoneNumber())
                : normalizeMsisdn(request.payerMsisdn());
        if (payer == null) {
            throw new IllegalArgumentException("The customer has no valid M-PESA number on file. Enter the number to prompt.");
        }
        terminal.setLastSeenAt(SystemTime.now());
        return paymentService.initiateAtTerminal(terminal, terminal.getAgent(), contract.getId(),
                request.amount(), payer, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public PaymentTransactionResponse status(String terminalCode, UserPrincipal principal, String reference) {
        Terminal terminal = requireOperable(terminalCode, principal);
        return terminalService.toTransactionResponse(requireOwnTransaction(terminal, reference));
    }

    /**
     * Prototype stand-in for the customer's phone: approve or decline the
     * M-PESA prompt. With a live M-PESA integration this is the provider's
     * callback, and this endpoint is switched off
     * ({@code app.payments.simulated-customer-phone=false}).
     */
    @Transactional
    public PaymentTransactionResponse simulateCustomerResponse(String terminalCode, UserPrincipal principal,
                                                               String reference, boolean approved) {
        if (!simulatedCustomerPhone) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer responses arrive from M-PESA.");
        }
        Terminal terminal = requireOperable(terminalCode, principal);
        PaymentTransaction transaction = requireOwnTransaction(terminal, reference);
        if (transaction.getStatus() != PaymentTransactionStatus.PENDING) {
            throw new IllegalArgumentException("This payment is no longer waiting for the customer ("
                    + transaction.getStatus() + ").");
        }
        return paymentService.completeStkPush(reference, approved,
                "Customer declined the M-PESA prompt (simulated).");
    }

    @Transactional(readOnly = true)
    public PosReceiptResponse receipt(String terminalCode, UserPrincipal principal, String reference) {
        Terminal terminal = requireOperable(terminalCode, principal);
        PaymentTransaction t = requireOwnTransaction(terminal, reference);
        String receiptNumber = null;
        BigDecimal balanceAfter = t.getContract().getAmountRemaining();
        if (t.getStatus() == PaymentTransactionStatus.POSTED || t.getStatus() == PaymentTransactionStatus.REVERSED) {
            var receipt = receiptRepository.findByTransactionId(t.getId()).orElse(null);
            if (receipt != null) {
                receiptNumber = receipt.getReceiptNumber();
                balanceAfter = receipt.getBalanceAfter();
            }
        }
        UnlockCodeResponse code = deviceLockService.codeIssuedFor(t.getId()).orElse(null);
        return new PosReceiptResponse(
                t.getStatus().name(),
                terminal.getMerchant().getBusinessName(),
                terminal.getTerminalCode(),
                t.getAgent() == null ? null : t.getAgent().getUserAccount().getFullName(),
                t.getTransactionReference(),
                t.getProviderReference(),
                receiptNumber,
                displayName(t.getCustomer().getFullName()),
                PaymentTransactionResponse.maskMsisdn(t.getPayerMsisdn()),
                t.getContract().getContractNumber(),
                t.getContract().getProduct().getName(),
                t.getAmount(),
                balanceAfter,
                t.getContract().getNextPaymentDueDate(),
                code == null ? null : code.code(),
                code == null ? null : code.validUntil(),
                code != null && code.permanent(),
                t.getFailureReason(),
                t.getRequestedAt(),
                t.getCompletedAt());
    }

    @Transactional(readOnly = true)
    public PosDaySummaryResponse daySummary(String terminalCode, UserPrincipal principal) {
        Terminal terminal = requireOperable(terminalCode, principal);
        LocalDate day = SystemTime.today();
        LocalDateTime from = day.atStartOfDay();
        List<PaymentTransaction> txns = transactionRepository
                .findAllByTerminalIdAndRequestedAtBetweenOrderByRequestedAtDesc(terminal.getId(), from, from.plusDays(1));
        long approved = txns.stream().filter(t -> t.getStatus() == PaymentTransactionStatus.POSTED).count();
        BigDecimal approvedAmount = txns.stream().filter(t -> t.getStatus() == PaymentTransactionStatus.POSTED)
                .map(PaymentTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long declined = txns.stream().filter(t -> t.getStatus() == PaymentTransactionStatus.FAILED).count();
        long pending = txns.stream().filter(t -> t.getStatus() == PaymentTransactionStatus.PENDING).count();
        return new PosDaySummaryResponse(terminal.getTerminalCode(), terminal.getMerchant().getBusinessName(),
                terminal.getAgent().getUserAccount().getFullName(), day, approved, approvedAmount, declined, pending,
                txns.stream().map(terminalService::toTransactionResponse).toList());
    }

    // ---- helpers ----

    private Terminal requireOperable(String terminalCode, UserPrincipal principal) {
        Terminal terminal = terminalRepository.findByTerminalCode(terminalCode == null ? "" : terminalCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Terminal not found."));
        if (terminal.getStatus() != TerminalStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Terminal " + terminal.getTerminalCode() + " is " + terminal.getStatus()
                            + ". Contact your merchant or support.");
        }
        Agent agent = agentRepository.findByUserAccountId(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only agents can operate terminals."));
        if (terminal.getAgent() == null || !terminal.getAgent().getId().equals(agent.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Terminal " + terminal.getTerminalCode() + " is not assigned to you.");
        }
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your agent account is " + agent.getStatus() + ".");
        }
        if (terminal.getMerchant().getStatus() != MerchantStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    terminal.getMerchant().getBusinessName() + " is " + terminal.getMerchant().getStatus() + ".");
        }
        return terminal;
    }

    private PaymentTransaction requireOwnTransaction(Terminal terminal, String reference) {
        PaymentTransaction t = transactionRepository.findByTransactionReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found."));
        if (t.getTerminal() == null || !t.getTerminal().getId().equals(terminal.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found on this terminal.");
        }
        return t;
    }

    private java.util.Optional<UserAccount> findCustomer(String q) {
        String upper = q.toUpperCase(Locale.ROOT);
        if (upper.startsWith("LN-")) {
            return contractRepository.findAll().stream()
                    .filter(c -> upper.equals(c.getContractNumber()))
                    .map(LoanContract::getCustomer).findFirst();
        }
        String msisdn = normalizeMsisdn(q);
        return userAccountRepository.findByRoleOrderByCreatedAtDesc(Role.CUSTOMER).stream()
                .filter(u -> upper.equals(u.getCustomerCode())
                        || (msisdn != null && msisdn.equals(normalizeMsisdn(u.getPhoneNumber()))))
                .findFirst();
    }

    private PosCustomerResponse.Contract toPosContract(LoanContract c) {
        return new PosCustomerResponse.Contract(
                c.getId(),
                c.getContractNumber(),
                c.getProduct().getName(),
                c.getDevice() == null ? null : c.getDevice().getDeviceCode(),
                c.getStatus().name(),
                deviceLockService.stateOf(c).name(),
                c.getDailyAmount(),
                amountDueNow(c),
                c.getAmountRemaining(),
                c.getNextPaymentDueDate());
    }

    /** Every installment from the next due date up to and including today, less what is already credited. */
    static BigDecimal amountDueNow(LoanContract c) {
        LocalDate due = c.getNextPaymentDueDate();
        LocalDate today = SystemTime.today();
        if (due == null || due.isAfter(today) || c.getDailyAmount() == null) {
            return BigDecimal.ZERO;
        }
        long days = ChronoUnit.DAYS.between(due, today) + 1;
        BigDecimal owed = c.getDailyAmount().multiply(BigDecimal.valueOf(days))
                .subtract(c.getInstallmentPaid() == null ? BigDecimal.ZERO : c.getInstallmentPaid());
        return owed.max(BigDecimal.ZERO).min(c.getAmountRemaining());
    }

    /** "0712 345 678", "+254712345678", "712345678" → "254712345678". */
    static String normalizeMsisdn(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("254") && digits.length() == 12) {
            return digits;
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            return "254" + digits.substring(1);
        }
        if (digits.length() == 9 && (digits.startsWith("7") || digits.startsWith("1"))) {
            return "254" + digits;
        }
        return null;
    }

    /** "Otieno Ouma" → "Otieno O." */
    private static String displayName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Customer";
        }
        String[] parts = fullName.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }
}
