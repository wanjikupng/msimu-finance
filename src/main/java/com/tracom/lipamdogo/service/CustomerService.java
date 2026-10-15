package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.CashLoanRequest;
import com.tracom.lipamdogo.dto.CashLoanResponse;
import com.tracom.lipamdogo.dto.DashboardResponse;
import com.tracom.lipamdogo.dto.LoanContractResponse;
import com.tracom.lipamdogo.dto.PaymentTimelineEntry;
import com.tracom.lipamdogo.dto.UserProfileResponse;
import com.tracom.lipamdogo.model.CashLoan;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.CashLoanRepository;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentReceiptRepository;
import com.tracom.lipamdogo.repository.PaymentTransactionRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.support.SystemTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CustomerService {

    private final UserAccountRepository userAccountRepository;
    private final LoanContractRepository loanContractRepository;
    private final CashLoanRepository cashLoanRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentReceiptRepository receiptRepository;

    public CustomerService(UserAccountRepository userAccountRepository,
                           LoanContractRepository loanContractRepository,
                           CashLoanRepository cashLoanRepository,
                           PaymentTransactionRepository transactionRepository,
                           PaymentReceiptRepository receiptRepository) {
        this.userAccountRepository = userAccountRepository;
        this.loanContractRepository = loanContractRepository;
        this.cashLoanRepository = cashLoanRepository;
        this.transactionRepository = transactionRepository;
        this.receiptRepository = receiptRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        return UserProfileResponse.from(requireAccount(userId));
    }

    /**
     * Dashboard for a single account. Callers pass the already-authorized user
     * id: the caller's own id, or — for ADMIN/LOAN_OFFICER endpoints — an id
     * the role check has explicitly approved.
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(Long userId) {
        UserAccount account = requireAccount(userId);

        List<LoanContract> contracts = loanContractRepository.findAllByCustomerId(userId);
        List<LoanContractResponse> deviceLoans = contracts.stream()
                .map(LoanContractResponse::from)
                .toList();

        List<CashLoanResponse> cashLoans = cashLoanRepository.findAllByCustomerId(userId)
                .stream()
                .map(CashLoanResponse::from)
                .toList();

        BigDecimal deviceBalance = contracts.stream()
                .map(c -> nullToZero(c.getAmountRemaining()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashBalance = cashLoans.stream()
                .map(CashLoanResponse::balanceRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalBalance = deviceBalance.add(cashBalance);

        // Total paid = cumulative POSTED payments (authoritative amountPaid),
        // never frontend state.
        BigDecimal devicePaid = contracts.stream()
                .map(c -> nullToZero(c.getAmountPaid()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashApproved = cashLoans.stream()
                .map(CashLoanResponse::approvedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPaid = devicePaid.add(cashApproved);

        BigDecimal totalFinanced = contracts.stream()
                .map(c -> nullToZero(c.getTotalPayable()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Arrears: for each live contract past its due date, the unpaid slice
        // of the currently due installment. Deterministic, server-computed.
        LocalDate today = SystemTime.today();
        BigDecimal arrears = BigDecimal.ZERO;
        BigDecimal todayExpected = BigDecimal.ZERO;
        boolean anyOverdue = false;
        for (LoanContract contract : contracts) {
            if (contract.getStatus() == LoanStatus.ACTIVE || contract.getStatus() == LoanStatus.OVERDUE) {
                todayExpected = todayExpected.add(nullToZero(contract.getDailyAmount()));
                if (contract.getStatus() == LoanStatus.OVERDUE) {
                    anyOverdue = true;
                }
                if (nullToZero(contract.getAmountRemaining()).compareTo(BigDecimal.ZERO) > 0
                        && contract.getNextPaymentDueDate() != null
                        && today.isAfter(contract.getNextPaymentDueDate())) {
                    BigDecimal unpaid = nullToZero(contract.getDailyAmount())
                            .subtract(nullToZero(contract.getInstallmentPaid()).min(nullToZero(contract.getDailyAmount())));
                    arrears = arrears.add(unpaid.max(BigDecimal.ZERO));
                }
            }
        }

        String currentStatus;
        if (contracts.isEmpty()) {
            currentStatus = "NONE";
        } else if (anyOverdue) {
            currentStatus = LoanStatus.OVERDUE.name();
        } else if (contracts.stream().allMatch(c -> c.getStatus() == LoanStatus.PAID_OFF)) {
            currentStatus = LoanStatus.PAID_OFF.name();
        } else {
            currentStatus = LoanStatus.ACTIVE.name();
        }

        Map<String, Object> paymentSummary = new HashMap<>();
        paymentSummary.put("activeLoans", deviceLoans.size() + cashLoans.size());
        paymentSummary.put("totalBalance", totalBalance);
        paymentSummary.put("totalPaid", totalPaid);
        paymentSummary.put("totalFinanced", totalFinanced);
        paymentSummary.put("outstandingDevices", deviceBalance);
        paymentSummary.put("paidDevices", devicePaid);
        paymentSummary.put("arrears", arrears);
        paymentSummary.put("todayExpected", todayExpected);
        paymentSummary.put("currentStatus", currentStatus);

        List<PaymentTimelineEntry> timeline = transactionRepository.findAllByCustomerId(userId).stream()
                .sorted(Comparator.comparing(
                        t -> t.getRequestedAt() == null ? LocalDateTime.MIN : t.getRequestedAt(),
                        Comparator.reverseOrder()))
                .limit(20)
                .map(t -> {
                    var receipt = receiptRepository.findByTransactionId(t.getId()).orElse(null);
                    return PaymentTimelineEntry.from(t,
                            receipt == null ? null : receipt.getReceiptNumber(),
                            receipt == null ? null : receipt.getBalanceAfter());
                })
                .toList();

        return new DashboardResponse(
                UserProfileResponse.from(account),
                deviceLoans,
                cashLoans,
                totalBalance,
                totalPaid,
                paymentSummary,
                timeline
        );
    }

    /**
     * Cash-loan application is always recorded for the authenticated user. The
     * request carries no customer id, so there is nothing to spoof.
     */
    @Transactional
    public CashLoanResponse createCashLoan(UserPrincipal principal, CashLoanRequest request) {
        UserAccount customer = requireAccount(principal.getId());

        CashLoan loan = new CashLoan();
        loan.setCustomer(customer);
        loan.setAmountRequested(request.amountRequested());
        loan.setApprovedAmount(request.amountRequested());
        loan.setBalanceRemaining(request.amountRequested());
        loan.setMonthlyRate(request.monthlyRate() == null ? new BigDecimal("0.00") : request.monthlyRate());
        loan.setPurpose(request.purpose() == null ? "General personal loan" : request.purpose());
        // NOTE (Phase 1): the instant-approval workflow is unchanged here. Phase 2
        // reworks this into a merchant/agent working-capital review lifecycle.
        loan.setStatus("APPROVED");

        return CashLoanResponse.from(cashLoanRepository.save(loan));
    }

    private UserAccount requireAccount(Long userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer account not found."));
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
