package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.PaymentAllocationResponse;
import com.tracom.lipamdogo.dto.PaymentInitiateRequest;
import com.tracom.lipamdogo.dto.PaymentTransactionResponse;
import com.tracom.lipamdogo.dto.ReceiptResponse;
import com.tracom.lipamdogo.model.Agent;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.PaymentOrigin;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.Terminal;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.payment.MockProviderScenario;
import com.tracom.lipamdogo.payment.PaymentProvider;
import com.tracom.lipamdogo.payment.PaymentProviderRegistry;
import com.tracom.lipamdogo.payment.ProviderOutcome;
import com.tracom.lipamdogo.payment.ProviderPaymentRequest;
import com.tracom.lipamdogo.payment.ProviderResult;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentAllocationRepository;
import com.tracom.lipamdogo.repository.PaymentReceiptRepository;
import com.tracom.lipamdogo.repository.PaymentTransactionRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.support.SystemTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Payment initiation and provider-result handling. Creates the transaction,
 * invokes the channel adapter, and hands SUCCESS outcomes to the posting
 * service — all in one transaction so a stored transaction always reflects
 * exactly what the provider reported.
 */
@Service
public class PaymentService {

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentReceiptRepository receiptRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final LoanContractRepository loanContractRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final PaymentPostingService postingService;

    public PaymentService(PaymentTransactionRepository transactionRepository,
                          PaymentReceiptRepository receiptRepository,
                          PaymentAllocationRepository allocationRepository,
                          LoanContractRepository loanContractRepository,
                          PaymentProviderRegistry providerRegistry,
                          PaymentPostingService postingService) {
        this.transactionRepository = transactionRepository;
        this.receiptRepository = receiptRepository;
        this.allocationRepository = allocationRepository;
        this.loanContractRepository = loanContractRepository;
        this.providerRegistry = providerRegistry;
        this.postingService = postingService;
    }

    /**
     * Transaction states whose amounts may still become financially effective
     * and therefore reserve contract capacity: anything initiated but not yet
     * conclusively settled. POSTED is consumed into the balance, FAILED is
     * released, REVERSED is rolled back — none of those reserve.
     */
    private static final Set<PaymentTransactionStatus> RESERVING_STATUSES = EnumSet.of(
            PaymentTransactionStatus.INITIATED,
            PaymentTransactionStatus.PENDING,
            PaymentTransactionStatus.SUCCESS);

    /**
     * Initiates a payment. The client supplies contract + amount + channel
     * only; status, references, and balances are server-controlled.
     * Repeating a request with the same Idempotency-Key returns the original
     * transaction instead of charging twice.
     */
    @Transactional
    public PaymentTransactionResponse initiate(UserPrincipal principal,
                                               PaymentInitiateRequest request,
                                               String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            PaymentTransaction existing = transactionRepository.findByIdempotencyKey(idempotencyKey.trim())
                    .orElse(null);
            if (existing != null) {
                assertVisible(principal, existing);
                assertSameIntent(existing, request);
                return toResponse(existing);
            }
        }

        LoanContract contract = loanContractRepository.findByIdForUpdate(request.contractId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Financing contract not found."));
        assertCanInitiate(principal, contract);

        // Re-check idempotency under the contract lock: a concurrent request
        // with the same key may have committed between the fast-path check
        // above and acquiring this lock.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            PaymentTransaction raced = transactionRepository.findByIdempotencyKey(idempotencyKey.trim())
                    .orElse(null);
            if (raced != null) {
                assertVisible(principal, raced);
                assertSameIntent(raced, request);
                return toResponse(raced);
            }
        }

        PaymentOrigin origin = principal.getRole() == Role.CUSTOMER
                ? PaymentOrigin.CUSTOMER_APP
                : PaymentOrigin.BACK_OFFICE;
        return send(contract, request.amount(), request.channel(),
                request.mockScenario() == null ? MockProviderScenario.AUTO : request.mockScenario(),
                idempotencyKey, origin, null, null, null);
    }

    /**
     * Agent-assisted M-PESA collection from a POS terminal. The provider is
     * asked to send a payment prompt (STK push) to the customer's phone; the
     * transaction stays PENDING until the customer approves or declines with
     * their M-PESA PIN ({@link #completeStkPush}). The agent never handles
     * money and never sees the customer's PIN.
     */
    @Transactional
    public PaymentTransactionResponse initiateAtTerminal(Terminal terminal, Agent agent, Long contractId,
                                                         BigDecimal amount, String payerMsisdn,
                                                         String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            PaymentTransaction existing = transactionRepository.findByIdempotencyKey(idempotencyKey.trim())
                    .orElse(null);
            if (existing != null) {
                assertSameTerminalIntent(existing, terminal, contractId, amount);
                return toResponse(existing);
            }
        }
        LoanContract contract = loanContractRepository.findByIdForUpdate(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Financing contract not found."));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            PaymentTransaction raced = transactionRepository.findByIdempotencyKey(idempotencyKey.trim())
                    .orElse(null);
            if (raced != null) {
                assertSameTerminalIntent(raced, terminal, contractId, amount);
                return toResponse(raced);
            }
        }
        return send(contract, amount, PaymentChannel.MPESA, MockProviderScenario.PENDING, idempotencyKey,
                PaymentOrigin.AGENT_TERMINAL, terminal, agent, payerMsisdn);
    }

    /**
     * Outcome of an STK push, as the M-PESA callback would report it: the
     * customer approved (SUCCESS, posted immediately like any synchronous
     * settlement) or declined / let it time out (FAILED, reservation released,
     * nothing applied).
     */
    @Transactional
    public PaymentTransactionResponse completeStkPush(String reference, boolean approved, String failureReason) {
        applyProviderResult(reference, approved ? ProviderOutcome.SUCCESS : ProviderOutcome.FAILED,
                approved ? null : failureReason);
        PaymentTransaction transaction = transactionRepository.findByTransactionReference(reference).orElseThrow();
        String receiptNumber = null;
        if (approved) {
            receiptNumber = postingService.post(reference).receiptNumber();
        } else {
            postingService.refreshLoanState(transaction.getContract());
        }
        return toResponse(transactionRepository.findById(transaction.getId()).orElseThrow(), receiptNumber);
    }

    private PaymentTransactionResponse send(LoanContract contract, BigDecimal amount, PaymentChannel channel,
                                            MockProviderScenario scenario, String idempotencyKey,
                                            PaymentOrigin origin, Terminal terminal, Agent agent,
                                            String payerMsisdn) {
        if (contract.getStatus() != LoanStatus.ACTIVE && contract.getStatus() != LoanStatus.OVERDUE) {
            throw new IllegalArgumentException(
                    "Payments are only accepted on ACTIVE or OVERDUE contracts (current: " + contract.getStatus() + ").");
        }
        if (contract.getAmountRemaining().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Contract has no outstanding balance.");
        }

        // Reservation guard: money already committed to earlier unposted
        // payments cannot be promised again. Computed under the contract
        // write lock, so concurrent initiations serialize here and can never
        // jointly reserve more than the outstanding balance.
        BigDecimal reserved = transactionRepository.sumReservedByContractId(contract.getId(), RESERVING_STATUSES);
        BigDecimal available = contract.getAmountRemaining().subtract(reserved);
        if (available.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "A previous payment of KES " + reserved.stripTrailingZeros().toPlainString()
                            + " is still being processed. Please wait for it to complete before making another payment.");
        }
        if (amount.compareTo(available) > 0) {
            if (reserved.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Payment of KES " + amount
                        + " exceeds the outstanding balance of KES " + contract.getAmountRemaining() + ".");
            }
            throw new IllegalArgumentException(
                    "Only KES " + available.stripTrailingZeros().toPlainString()
                            + " is available right now — KES " + reserved.stripTrailingZeros().toPlainString()
                            + " is held by a payment that is still being processed. "
                            + "Please wait for it to complete, or pay KES "
                            + available.stripTrailingZeros().toPlainString() + " or less.");
        }

        PaymentProvider provider = providerRegistry.require(channel);
        UserAccount customer = contract.getCustomer();

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setContract(contract);
        transaction.setCustomer(customer);
        transaction.setAmount(amount);
        transaction.setChannel(channel);
        transaction.setOrigin(origin);
        transaction.setTerminal(terminal);
        transaction.setAgent(agent);
        transaction.setPayerMsisdn(payerMsisdn);
        transaction.setStatus(PaymentTransactionStatus.INITIATED);
        transaction.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString()
                : idempotencyKey.trim());
        PaymentTransaction saved = transactionRepository.saveAndFlush(transaction);
        saved.setTransactionReference(number("PAY", saved.getId()));
        transactionRepository.save(saved);

        ProviderResult result = provider.initiate(new ProviderPaymentRequest(
                saved.getId(),
                saved.getTransactionReference(),
                saved.getAmount(),
                contract.getId(),
                customer.getId(),
                scenario));

        applyProviderResult(saved, result);
        postingService.refreshLoanState(contract);

        String receiptNumber = null;
        if (saved.getStatus() == PaymentTransactionStatus.SUCCESS) {
            // Synchronous settlement: an immediate provider SUCCESS is posted
            // by the engine (system actor) in the same transaction. Deferred
            // SUCCESS outcomes (via provider-result) require explicit posting.
            ReceiptResponse receipt = postingService.post(saved.getTransactionReference());
            receiptNumber = receipt.receiptNumber();
        }

        return toResponse(transactionRepository.findById(saved.getId()).orElseThrow(), receiptNumber);
    }

    /**
     * Applies a deferred provider outcome to a PENDING transaction. Restricted
     * to ADMIN (simulation hook): customers can never mark their own payments.
     * Repeated callbacks are safe — only PENDING rows move, and posting stays
     * a separate explicit step.
     */
    @Transactional
    public PaymentTransactionResponse applyProviderResult(String reference,
                                                          ProviderOutcome outcome,
                                                          String failureReason) {
        PaymentTransaction transaction = transactionRepository.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found."));
        if (transaction.getStatus() != PaymentTransactionStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Provider results apply to PENDING transactions only (current: " + transaction.getStatus() + ").");
        }
        if (outcome == ProviderOutcome.SUCCESS) {
            transaction.setStatus(PaymentTransactionStatus.SUCCESS);
            transaction.setCompletedAt(SystemTime.now());
        } else if (outcome == ProviderOutcome.FAILED) {
            transaction.setStatus(PaymentTransactionStatus.FAILED);
            transaction.setFailureReason(failureReason == null || failureReason.isBlank()
                    ? "Provider reported failure (simulation)."
                    : failureReason.trim());
            transaction.setCompletedAt(SystemTime.now());
        } else {
            throw new IllegalArgumentException("Provider result must be SUCCESS or FAILED.");
        }
        transaction.setUpdatedAt(SystemTime.now());
        transactionRepository.save(transaction);
        return toResponse(transaction);
    }

    @Transactional(readOnly = true)
    public List<PaymentTransactionResponse> myTransactions(UserPrincipal principal,
                                                           PaymentTransactionStatus status) {
        List<PaymentTransaction> transactions = status == null
                ? transactionRepository.findAllByCustomerId(principal.getId())
                : transactionRepository.findAllByCustomerIdAndStatus(principal.getId(), status);
        return transactions.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PaymentTransactionResponse getTransaction(UserPrincipal principal, String reference) {
        PaymentTransaction transaction = transactionRepository.findByTransactionReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found."));
        assertVisible(principal, transaction);
        return toResponse(transaction);
    }

    @Transactional(readOnly = true)
    public List<PaymentAllocationResponse> getAllocations(UserPrincipal principal, String reference) {
        PaymentTransaction transaction = transactionRepository.findByTransactionReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found."));
        assertVisible(principal, transaction);
        return allocationRepository.findByPaymentTransactionId(transaction.getId()).stream()
                .sorted((a, b) -> Integer.compare(
                        a.getSequence() == null ? 0 : a.getSequence(),
                        b.getSequence() == null ? 0 : b.getSequence()))
                .map(PaymentAllocationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentTransactionResponse adminGet(String reference) {
        PaymentTransaction transaction = transactionRepository.findByTransactionReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found."));
        return toResponse(transaction);
    }

    @Transactional(readOnly = true)
    public List<PaymentTransactionResponse> adminList(PaymentTransactionStatus status,
                                                      Long contractId,
                                                      Long customerId) {
        List<PaymentTransaction> transactions;
        if (contractId != null && status != null) {
            transactions = transactionRepository.findAllByContractIdAndStatus(contractId, status);
        } else if (contractId != null) {
            transactions = transactionRepository.findAllByContractId(contractId);
        } else if (customerId != null && status != null) {
            transactions = transactionRepository.findAllByCustomerIdAndStatus(customerId, status);
        } else if (customerId != null) {
            transactions = transactionRepository.findAllByCustomerId(customerId);
        } else if (status != null) {
            transactions = transactionRepository.findAllByStatus(status);
        } else {
            transactions = transactionRepository.findAll();
        }
        return transactions.stream().map(this::toResponse).toList();
    }

    private void applyProviderResult(PaymentTransaction transaction, ProviderResult result) {
        switch (result.outcome()) {
            case SUCCESS -> {
                transaction.setStatus(PaymentTransactionStatus.SUCCESS);
                transaction.setProviderReference(result.providerReference());
                transaction.setCompletedAt(SystemTime.now());
            }
            case PENDING -> {
                transaction.setStatus(PaymentTransactionStatus.PENDING);
                transaction.setProviderReference(result.providerReference());
            }
            case FAILED -> {
                transaction.setStatus(PaymentTransactionStatus.FAILED);
                transaction.setProviderReference(result.providerReference());
                transaction.setFailureReason(result.failureReason());
                transaction.setCompletedAt(SystemTime.now());
            }
        }
        transaction.setUpdatedAt(SystemTime.now());
        transactionRepository.save(transaction);
    }

    private void assertCanInitiate(UserPrincipal principal, LoanContract contract) {
        if (principal.getRole() == Role.CUSTOMER) {
            if (!contract.getCustomer().getId().equals(principal.getId())) {
                throw new AccessDeniedException("You may only pay your own financing contracts.");
            }
            return;
        }
        if (principal.getRole() == Role.PAYMENTS_OPERATOR || principal.getRole() == Role.ADMIN) {
            return;
        }
        throw new AccessDeniedException("Your role cannot initiate payments.");
    }

    private void assertVisible(UserPrincipal principal, PaymentTransaction transaction) {
        if (transaction.getCustomer().getId().equals(principal.getId()) || principal.isStaff()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found.");
    }

    private void assertSameIntent(PaymentTransaction existing, PaymentInitiateRequest request) {
        boolean same = existing.getContract().getId().equals(request.contractId())
                && existing.getAmount().compareTo(request.amount()) == 0
                && existing.getChannel() == request.channel();
        if (!same) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency key was already used for a different payment.");
        }
    }

    private void assertSameTerminalIntent(PaymentTransaction existing, Terminal terminal, Long contractId,
                                          BigDecimal amount) {
        boolean same = existing.getTerminal() != null
                && existing.getTerminal().getId().equals(terminal.getId())
                && existing.getContract().getId().equals(contractId)
                && existing.getAmount().compareTo(amount) == 0;
        if (!same) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency key was already used for a different payment.");
        }
    }

    private PaymentTransactionResponse toResponse(PaymentTransaction transaction) {
        String receiptNumber = null;
        if (transaction.getStatus() == PaymentTransactionStatus.POSTED) {
            receiptNumber = receiptRepository.findByTransactionId(transaction.getId())
                    .map(r -> r.getReceiptNumber())
                    .orElse(null);
        }
        return toResponse(transaction, receiptNumber);
    }

    private PaymentTransactionResponse toResponse(PaymentTransaction transaction, String receiptNumber) {
        return PaymentTransactionResponse.from(transaction, receiptNumber);
    }

    private String number(String prefix, Long id) {
        return "%s-%d-%06d".formatted(prefix, SystemTime.today().getYear(), id);
    }
}
