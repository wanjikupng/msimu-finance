package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.ReceiptResponse;
import com.tracom.lipamdogo.model.AllocationStatus;
import com.tracom.lipamdogo.model.Device;
import com.tracom.lipamdogo.model.DeviceStatus;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.model.PaymentAllocation;
import com.tracom.lipamdogo.model.PaymentReceipt;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentAllocationRepository;
import com.tracom.lipamdogo.repository.PaymentReceiptRepository;
import com.tracom.lipamdogo.repository.PaymentTransactionRepository;
import com.tracom.lipamdogo.support.SystemTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies provider-confirmed money to contracts. The ONLY writer of the
 * contract balance trio (amountPaid / amountRemaining / installmentPaid).
 * Every operation is one transaction with write locks on the payment row and
 * the contract row; concurrent attempts serialize and the financial effect
 * lands exactly once.
 */
@Service
public class PaymentPostingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentPostingService.class);

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentReceiptRepository receiptRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final LoanContractRepository loanContractRepository;
    private final DeviceService deviceService;
    private final DeviceLockService deviceLockService;

    public PaymentPostingService(PaymentTransactionRepository transactionRepository,
                                 PaymentReceiptRepository receiptRepository,
                                 PaymentAllocationRepository allocationRepository,
                                 LoanContractRepository loanContractRepository,
                                 DeviceService deviceService,
                                 DeviceLockService deviceLockService) {
        this.transactionRepository = transactionRepository;
        this.receiptRepository = receiptRepository;
        this.allocationRepository = allocationRepository;
        this.loanContractRepository = loanContractRepository;
        this.deviceService = deviceService;
        this.deviceLockService = deviceLockService;
    }

    /**
     * Posts a SUCCESS transaction. Idempotent: an already-POSTED transaction
     * returns its existing receipt with no further financial effect.
     */
    @Transactional
    public ReceiptResponse post(String transactionReference) {
        PaymentTransaction transaction = lockTransaction(transactionReference);
        if (transaction.getStatus() == PaymentTransactionStatus.POSTED) {
            return ReceiptResponse.from(requireReceipt(transaction));
        }
        if (transaction.getStatus() != PaymentTransactionStatus.SUCCESS) {
            throw new IllegalArgumentException(
                    "Only SUCCESS transactions can be posted (current: " + transaction.getStatus() + ").");
        }

        LoanContract contract = lockContract(transaction.getContract().getId());
        if (contract.getStatus() == LoanStatus.PAID_OFF
                || contract.getAmountRemaining().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Contract has no outstanding balance to apply.");
        }

        // Strict invariant: initiation guarantees amount <= available capacity,
        // so a shortfall here means reservation accounting was bypassed or
        // corrupted. Fail loudly and apply nothing — never silently discard
        // part of an accepted provider transaction via min().
        if (transaction.getAmount().compareTo(contract.getAmountRemaining()) > 0) {
            throw new IllegalStateException(
                    "Payment " + transaction.getTransactionReference()
                            + " for KES " + transaction.getAmount().stripTrailingZeros().toPlainString()
                            + " exceeds the contract's remaining balance of KES "
                            + contract.getAmountRemaining().stripTrailingZeros().toPlainString()
                            + ". Reservation accounting is inconsistent; the payment was NOT applied and requires investigation.");
        }
        BigDecimal apply = transaction.getAmount();

        // Allocation ledger: explain the whole payment as day slices BEFORE
        // mutating the contract, in the same transaction. Rows always sum to
        // exactly the applied amount (asserted inside).
        LocalDate allocationStart = contract.getNextPaymentDueDate() != null
                ? contract.getNextPaymentDueDate()
                : contract.getScheduleAnchorDate();
        buildAllocationRows(transaction, contract, allocationStart,
                contract.getInstallmentPaid(), apply, contract.getDailyAmount());

        contract.setAmountPaid(contract.getAmountPaid().add(apply));
        contract.setInstallmentPaid(contract.getInstallmentPaid().add(apply));
        contract.setAmountRemaining(contract.getTotalPayable().subtract(contract.getAmountPaid()));
        rollCoveredInstallments(contract);

        transaction.setPostedAmount(apply);
        transaction.setStatus(PaymentTransactionStatus.POSTED);
        transaction.setPostedAt(SystemTime.now());
        transaction.setUpdatedAt(SystemTime.now());
        transactionRepository.save(transaction);

        refreshLoanState(contract);
        deviceLockService.refresh(contract, transaction, false);

        PaymentReceipt receipt = new PaymentReceipt();
        receipt.setTransaction(transaction);
        receipt.setAmountApplied(apply);
        receipt.setBalanceAfter(contract.getAmountRemaining());
        receipt.setPostedAt(transaction.getPostedAt());
        PaymentReceipt saved = receiptRepository.saveAndFlush(receipt);
        saved.setReceiptNumber(number("RCPT", saved.getId()));
        receiptRepository.save(saved);

        return ReceiptResponse.from(saved);
    }

    /**
     * Rolls back a POSTED payment: POSTED → REVERSED with an explicit reason.
     *
     * <p>Allocation-based: the payment's ACTIVE allocation rows are marked
     * REVERSED (preserved as history), and the installment schedule is rebuilt
     * by replaying the remaining ACTIVE rows forward from the contract's
     * schedule anchor — the exact state as if this payment had never posted.
     * Unrelated payments' rows are never touched.</p>
     *
     * <p>Payoff guard: reversing a payment on a PAID_OFF contract would reopen
     * it while the device stays PAID_OFF (a terminal physical state), so that
     * reversal is refused with a business error instead of creating an
     * impossible cross-domain state. The device is otherwise untouched.</p>
     */
    @Transactional
    public PaymentTransaction reverse(String transactionReference, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reversal reason is required.");
        }
        PaymentTransaction transaction = lockTransaction(transactionReference);
        if (transaction.getStatus() != PaymentTransactionStatus.POSTED) {
            throw new IllegalArgumentException(
                    "Only POSTED transactions can be reversed (current: " + transaction.getStatus() + ").");
        }

        LoanContract contract = lockContract(transaction.getContract().getId());
        if (contract.getStatus() == LoanStatus.PAID_OFF) {
            throw new IllegalArgumentException(
                    "This payment cannot be reversed because it completed the financing contract. "
                            + "A controlled finance adjustment is required.");
        }
        BigDecimal posted = transaction.getPostedAmount() == null ? BigDecimal.ZERO : transaction.getPostedAmount();
        contract.setAmountPaid(contract.getAmountPaid().subtract(posted).max(BigDecimal.ZERO));
        contract.setAmountRemaining(contract.getTotalPayable().subtract(contract.getAmountPaid()));

        LocalDateTime now = SystemTime.now();
        List<PaymentAllocation> rows =
                allocationRepository.findByPaymentTransactionIdAndStatus(transaction.getId(), AllocationStatus.ACTIVE);
        for (PaymentAllocation row : rows) {
            row.setStatus(AllocationStatus.REVERSED);
            row.setReversedAt(now);
            allocationRepository.save(row);
        }

        rebuildSchedule(contract);

        transaction.setStatus(PaymentTransactionStatus.REVERSED);
        transaction.setReversalReference("REV-" + transaction.getId());
        transaction.setReversalReason(reason.trim());
        transaction.setUpdatedAt(SystemTime.now());
        transactionRepository.save(transaction);

        refreshLoanState(contract);
        deviceLockService.refresh(contract, transaction, true);
        return transaction;
    }

    /**
     * Recomputes the contract state from time + money, never from client
     * input. Called after initiation outcomes, postings, and reversals.
     * Rule: balance 0 → PAID_OFF; else past the due date with an incompletely
     * paid installment → OVERDUE; otherwise ACTIVE.
     */
    public void refreshLoanState(LoanContract contract) {
        if (contract.getAmountRemaining().compareTo(BigDecimal.ZERO) <= 0) {
            contract.setAmountRemaining(BigDecimal.ZERO);
            contract.setInstallmentPaid(BigDecimal.ZERO);
            if (contract.getStatus() != LoanStatus.PAID_OFF) {
                contract.setStatus(LoanStatus.PAID_OFF);
                payoffDevice(contract);
            }
            return;
        }
        if (SystemTime.today().isAfter(contract.getNextPaymentDueDate())
                && contract.getInstallmentPaid().compareTo(contract.getDailyAmount()) < 0) {
            contract.setStatus(LoanStatus.OVERDUE);
        } else {
            contract.setStatus(LoanStatus.ACTIVE);
        }
        syncDeviceWithArrears(contract);
    }

    /**
     * Keeps the physical device in step with the contract: a device whose
     * contract falls into arrears moves FINANCED → OVERDUE (the state a
     * device-lock integration acts on), and back to FINANCED once the
     * customer catches up. Any other device state (repair, lost) is left alone.
     */
    private void syncDeviceWithArrears(LoanContract contract) {
        Device device = contract.getDevice();
        if (device == null) {
            return;
        }
        if (contract.getStatus() == LoanStatus.OVERDUE && device.getStatus() == DeviceStatus.FINANCED) {
            deviceService.markInArrears(device.getId());
        } else if (contract.getStatus() == LoanStatus.ACTIVE && device.getStatus() == DeviceStatus.OVERDUE) {
            deviceService.clearArrears(device.getId());
        }
    }

    /**
     * Splits one posted payment across day obligations, oldest first, and
     * persists one row per touched day. Mirrors {@link #rollCoveredInstallments}
     * exactly (first fill the current day's remainder, then whole days, then a
     * final partial day), so rows always agree with the resulting contract
     * fields. The server decides; the frontend never selects allocation dates.
     */
    private void buildAllocationRows(PaymentTransaction transaction, LoanContract contract,
                                     LocalDate startDay, BigDecimal carriedCredit,
                                     BigDecimal apply, BigDecimal installment) {
        List<PaymentAllocation> rows = new ArrayList<>();
        if (installment == null || installment.compareTo(BigDecimal.ZERO) <= 0 || startDay == null) {
            rows.add(allocationRow(transaction, contract, startDay, apply, 1));
        } else {
            BigDecimal dayNeed = installment.subtract(carriedCredit == null ? BigDecimal.ZERO : carriedCredit);
            LocalDate day = startDay;
            int sequence = 1;
            if (dayNeed.signum() <= 0) {
                // Defensive: carried credit should always be below one full
                // installment here (the roll loop guarantees it). Advance
                // rather than emitting a zero row.
                day = day.plusDays(1);
                dayNeed = installment;
            }
            BigDecimal left = apply;
            BigDecimal first = left.min(dayNeed);
            rows.add(allocationRow(transaction, contract, day, first, sequence++));
            left = left.subtract(first);
            while (left.signum() > 0) {
                day = day.plusDays(1);
                BigDecimal slice = left.min(installment);
                rows.add(allocationRow(transaction, contract, day, slice, sequence++));
                left = left.subtract(slice);
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentAllocation row : rows) {
            allocationRepository.save(row);
            total = total.add(row.getAmount());
        }
        if (total.compareTo(apply) != 0) {
            throw new IllegalStateException(
                    "Allocation rows sum to KES " + total + " but the payment applied KES " + apply + ".");
        }
    }

    private PaymentAllocation allocationRow(PaymentTransaction transaction, LoanContract contract,
                                            LocalDate day, BigDecimal amount, int sequence) {
        PaymentAllocation row = new PaymentAllocation();
        row.setPaymentTransaction(transaction);
        row.setContract(contract);
        row.setAllocationDate(day);
        row.setAmount(amount);
        row.setSequence(sequence);
        row.setStatus(AllocationStatus.ACTIVE);
        return row;
    }

    /**
     * Rebuilds (nextPaymentDueDate, installmentPaid) by replaying the
     * contract's remaining ACTIVE allocation rows forward from the schedule
     * anchor with the same roll arithmetic used at posting time. The result
     * is exactly the schedule as if reversed payments had never posted, while
     * unrelated payments' effects are preserved untouched.
     */
    private void rebuildSchedule(LoanContract contract) {
        List<PaymentAllocation> active =
                allocationRepository.findAllByContractIdAndStatusOrderByIdAsc(contract.getId(), AllocationStatus.ACTIVE);
        LocalDate anchor = contract.getScheduleAnchorDate();
        BigDecimal installment = contract.getDailyAmount();
        if (anchor == null || installment == null || installment.compareTo(BigDecimal.ZERO) <= 0) {
            contract.setInstallmentPaid(BigDecimal.ZERO);
            if (contract.getNextPaymentDueDate() == null) {
                contract.setNextPaymentDueDate(anchor);
            }
            return;
        }
        LocalDate due = anchor;
        BigDecimal credit = BigDecimal.ZERO;
        for (PaymentAllocation row : active) {
            credit = credit.add(row.getAmount());
            while (credit.compareTo(installment) >= 0) {
                credit = credit.subtract(installment);
                due = due.plusDays(1);
            }
        }
        contract.setNextPaymentDueDate(due);
        contract.setInstallmentPaid(credit);
    }

    /**
     * Allocation strategy (DAILY cadence): each posting credits the currently
     * due installment; every fully covered installment advances the due date
     * by one day and the remainder rolls forward. Partial payments therefore
     * always count — nothing is discarded for being below the installment.
     */
    private void rollCoveredInstallments(LoanContract contract) {
        if (contract.getAmountRemaining().compareTo(BigDecimal.ZERO) == 0) {
            contract.setInstallmentPaid(BigDecimal.ZERO);
            return;
        }
        BigDecimal installment = contract.getDailyAmount();
        if (installment == null || installment.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal credited = contract.getInstallmentPaid();
        LocalDate dueDate = contract.getNextPaymentDueDate();
        while (credited.compareTo(installment) >= 0 && dueDate != null) {
            credited = credited.subtract(installment);
            dueDate = dueDate.plusDays(1);
        }
        contract.setInstallmentPaid(credited);
        contract.setNextPaymentDueDate(dueDate);
    }

    private void payoffDevice(LoanContract contract) {
        Device device = contract.getDevice();
        if (device == null) {
            return;
        }
        if (device.getStatus() == DeviceStatus.FINANCED || device.getStatus() == DeviceStatus.OVERDUE) {
            deviceService.changeStatus(device.getId(),
                    new com.tracom.lipamdogo.dto.DeviceStatusRequest(DeviceStatus.PAID_OFF));
        } else {
            log.warn("Contract {} paid off while device {} is {}; leaving device untouched.",
                    contract.getContractNumber(), device.getDeviceCode(), device.getStatus());
        }
    }

    private PaymentTransaction lockTransaction(String reference) {
        return transactionRepository.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found."));
    }

    private LoanContract lockContract(Long contractId) {
        return loanContractRepository.findByIdForUpdate(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Financing contract not found."));
    }

    private PaymentReceipt requireReceipt(PaymentTransaction transaction) {
        return receiptRepository.findByTransactionId(transaction.getId())
                .orElseThrow(() -> new IllegalStateException("Posted transaction has no receipt."));
    }

    @Transactional(readOnly = true)
    public ReceiptResponse receiptFor(String transactionReference) {
        PaymentTransaction transaction = transactionRepository.findByTransactionReference(transactionReference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found."));
        // Receipts are durable history: retrievable for POSTED payments and
        // for REVERSED ones (which keep their original receipt as evidence).
        if (transaction.getStatus() != PaymentTransactionStatus.POSTED
                && transaction.getStatus() != PaymentTransactionStatus.REVERSED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not available.");
        }
        return ReceiptResponse.from(requireReceipt(transaction));
    }

    private String number(String prefix, Long id) {
        return "%s-%d-%06d".formatted(prefix, SystemTime.today().getYear(), id);
    }
}
