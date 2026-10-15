package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.SettlementBatchResponse;
import com.tracom.lipamdogo.dto.SettlementLineResponse;
import com.tracom.lipamdogo.model.BeneficiaryType;
import com.tracom.lipamdogo.model.CommissionEntry;
import com.tracom.lipamdogo.model.CommissionStatus;
import com.tracom.lipamdogo.model.CommissionType;
import com.tracom.lipamdogo.model.SettlementBatch;
import com.tracom.lipamdogo.model.SettlementBatchStatus;
import com.tracom.lipamdogo.model.SettlementLine;
import com.tracom.lipamdogo.model.SettlementLineStatus;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.payment.MockMpesaPayoutProvider;
import com.tracom.lipamdogo.repository.CommissionEntryRepository;
import com.tracom.lipamdogo.repository.SettlementBatchRepository;
import com.tracom.lipamdogo.repository.SettlementLineRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.support.Msisdn;
import com.tracom.lipamdogo.support.SystemTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Commission settlement: periodic M-PESA payouts to agents and merchants.
 *
 * <ol>
 *   <li><b>Run</b> (maker): every ACCRUED commission earned up to the cut-off
 *       is netted per partner (earnings minus clawbacks) into a payout line;
 *       those entries move to IN_SETTLEMENT so no other run can take them.
 *       M-PESA pays whole shillings, so the payout is rounded down and the
 *       cents are carried into the next run (a ROUNDING_CARRY pair). A
 *       partner owed less than KES 1 is carried forward entirely.</li>
 *   <li><b>Approve</b> (checker, a <em>different</em> staff member): each line
 *       is paid over M-PESA (B2C to the agent's phone, B2B to the merchant).
 *       Paid lines settle their entries; a failed line returns its entries to
 *       ACCRUED for the next run.</li>
 *   <li><b>Reject</b>: nothing is paid; every entry returns to ACCRUED.</li>
 * </ol>
 */
@Service
public class SettlementService {

    private final CommissionEntryRepository entryRepository;
    private final SettlementBatchRepository batchRepository;
    private final SettlementLineRepository lineRepository;
    private final UserAccountRepository userAccountRepository;
    private final MockMpesaPayoutProvider payoutProvider;

    public SettlementService(CommissionEntryRepository entryRepository,
                             SettlementBatchRepository batchRepository,
                             SettlementLineRepository lineRepository,
                             UserAccountRepository userAccountRepository,
                             MockMpesaPayoutProvider payoutProvider) {
        this.entryRepository = entryRepository;
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
        this.userAccountRepository = userAccountRepository;
        this.payoutProvider = payoutProvider;
    }

    @Transactional
    public SettlementBatchResponse run(LocalDate cutoffDate, UserPrincipal maker) {
        if (cutoffDate.isAfter(SystemTime.today())) {
            throw new IllegalArgumentException("The cut-off date cannot be in the future.");
        }
        List<CommissionEntry> entries = entryRepository.findForSettlement(CommissionStatus.ACCRUED,
                cutoffDate.plusDays(1).atStartOfDay());
        Map<String, List<CommissionEntry>> byPartner = new LinkedHashMap<>();
        for (CommissionEntry e : entries) {
            byPartner.computeIfAbsent(CommissionService.key(e), k -> new ArrayList<>()).add(e);
        }

        UserAccount makerAccount = userAccountRepository.findById(maker.getId()).orElseThrow();
        SettlementBatch batch = new SettlementBatch();
        batch.setCutoffDate(cutoffDate);
        batch.setStatus(SettlementBatchStatus.PENDING_APPROVAL);
        batch.setCreatedBy(makerAccount);
        batch.setTotalAmount(BigDecimal.ZERO);
        batch = batchRepository.saveAndFlush(batch);
        batch.setBatchNumber("STL-%d-%06d".formatted(SystemTime.today().getYear(), batch.getId()));

        BigDecimal total = BigDecimal.ZERO;
        int lines = 0;
        for (List<CommissionEntry> group : byPartner.values()) {
            BigDecimal earned = group.stream().map(CommissionEntry::getAmount)
                    .filter(a -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal clawback = group.stream().map(CommissionEntry::getAmount)
                    .filter(a -> a.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal net = earned.add(clawback);
            BigDecimal payable = net.setScale(0, RoundingMode.FLOOR);
            if (payable.signum() <= 0) {
                continue; // carried forward: less than a shilling (or nothing) is owed yet
            }
            BigDecimal cents = net.subtract(payable);
            CommissionEntry first = group.get(0);
            boolean agent = first.getBeneficiaryType() == BeneficiaryType.AGENT;
            SettlementLine line = new SettlementLine();
            line.setBatch(batch);
            line.setBeneficiaryType(first.getBeneficiaryType());
            line.setAgent(first.getAgent());
            line.setMerchant(first.getMerchant());
            line.setPayeeName(agent ? first.getAgent().getUserAccount().getFullName()
                    : first.getMerchant().getBusinessName());
            line.setPayeeMsisdn(Msisdn.normalize(agent ? first.getAgent().getUserAccount().getPhoneNumber()
                    : first.getMerchant().getPhone()));
            line.setPayoutMethod(agent ? "MPESA_B2C" : "MPESA_B2B");
            line.setEntryCount(group.size());
            line.setEarnedAmount(earned);
            line.setClawbackAmount(clawback);
            line.setCarriedForward(cents);
            line.setNetAmount(payable);
            line.setStatus(SettlementLineStatus.PENDING);
            line = lineRepository.save(line);
            List<CommissionEntry> settled = new ArrayList<>(group);
            if (cents.signum() > 0) {
                settled.add(roundingCarry(first, cents.negate(), CommissionStatus.IN_SETTLEMENT));
                entryRepository.save(roundingCarry(first, cents, CommissionStatus.ACCRUED));
            }
            for (CommissionEntry e : settled) {
                e.setStatus(CommissionStatus.IN_SETTLEMENT);
                e.setSettlementLine(line);
            }
            entryRepository.saveAll(settled);
            line.setEntryCount(group.size());
            total = total.add(payable);
            lines++;
        }
        if (lines == 0) {
            throw new IllegalArgumentException("Nothing to settle: no partner is owed commission up to "
                    + cutoffDate + ".");
        }
        batch.setLineCount(lines);
        batch.setTotalAmount(total);
        return toResponse(batchRepository.save(batch));
    }

    /** The checker approves and the payouts go out. The maker cannot approve their own batch. */
    @Transactional
    public SettlementBatchResponse approve(Long batchId, UserPrincipal checker, String note) {
        SettlementBatch batch = requirePending(batchId);
        if (batch.getCreatedBy().getId().equals(checker.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Maker-checker: a settlement must be approved by someone other than the person who prepared it.");
        }
        boolean allPaid = true;
        for (SettlementLine line : lineRepository.findAllByBatchIdOrderByIdAsc(batch.getId())) {
            MockMpesaPayoutProvider.PayoutResult result =
                    payoutProvider.send(line.getPayoutMethod(), line.getPayeeMsisdn(), line.getNetAmount());
            List<CommissionEntry> entries = entryRepository.findAllBySettlementLineId(line.getId());
            if (result.success()) {
                line.setStatus(SettlementLineStatus.PAID);
                line.setPayoutReference(result.reference());
                line.setPaidAt(SystemTime.now());
                entries.forEach(e -> e.setStatus(CommissionStatus.SETTLED));
            } else {
                allPaid = false;
                line.setStatus(SettlementLineStatus.FAILED);
                line.setFailureReason(result.failureReason());
                release(entries);
            }
            entryRepository.saveAll(entries);
            lineRepository.save(line);
        }
        batch.setStatus(allPaid ? SettlementBatchStatus.PAID : SettlementBatchStatus.PARTIALLY_PAID);
        decide(batch, checker, note);
        return toResponse(batchRepository.save(batch));
    }

    @Transactional
    public SettlementBatchResponse reject(Long batchId, UserPrincipal checker, String note) {
        SettlementBatch batch = requirePending(batchId);
        for (SettlementLine line : lineRepository.findAllByBatchIdOrderByIdAsc(batch.getId())) {
            List<CommissionEntry> entries = entryRepository.findAllBySettlementLineId(line.getId());
            release(entries);
            entryRepository.saveAll(entries);
            line.setStatus(SettlementLineStatus.FAILED);
            line.setFailureReason("Batch rejected" + (note == null || note.isBlank() ? "." : ": " + note.trim()));
            lineRepository.save(line);
        }
        batch.setStatus(SettlementBatchStatus.REJECTED);
        decide(batch, checker, note);
        return toResponse(batchRepository.save(batch));
    }

    @Transactional(readOnly = true)
    public List<SettlementBatchResponse> list() {
        return batchRepository.findAllByOrderByIdDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SettlementBatchResponse get(Long batchId) {
        return toResponse(batchRepository.findById(batchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Settlement batch not found.")));
    }

    private static CommissionEntry roundingCarry(CommissionEntry like, BigDecimal amount, CommissionStatus status) {
        CommissionEntry e = new CommissionEntry();
        e.setBeneficiaryType(like.getBeneficiaryType());
        e.setAgent(like.getAgent());
        e.setMerchant(like.getMerchant());
        e.setCommissionType(CommissionType.ROUNDING_CARRY);
        e.setBaseAmount(BigDecimal.ZERO);
        e.setAmount(amount);
        e.setStatus(status);
        e.setEarnedAt(SystemTime.now());
        return e;
    }

    private void release(List<CommissionEntry> entries) {
        for (CommissionEntry e : entries) {
            e.setStatus(CommissionStatus.ACCRUED);
            e.setSettlementLine(null);
        }
    }

    private void decide(SettlementBatch batch, UserPrincipal checker, String note) {
        batch.setDecidedBy(userAccountRepository.findById(checker.getId()).orElseThrow());
        batch.setDecidedAt(SystemTime.now());
        batch.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
    }

    private SettlementBatch requirePending(Long batchId) {
        SettlementBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Settlement batch not found."));
        if (batch.getStatus() != SettlementBatchStatus.PENDING_APPROVAL) {
            throw new IllegalArgumentException("This batch has already been decided (" + batch.getStatus() + ").");
        }
        return batch;
    }

    private SettlementBatchResponse toResponse(SettlementBatch b) {
        List<SettlementLineResponse> lines = b.getId() == null ? List.of()
                : lineRepository.findAllByBatchIdOrderByIdAsc(b.getId()).stream().map(SettlementLineResponse::from).toList();
        return new SettlementBatchResponse(
                b.getId(),
                b.getBatchNumber(),
                b.getCutoffDate(),
                b.getStatus().name(),
                b.getLineCount(),
                b.getTotalAmount(),
                b.getCreatedBy().getId(),
                b.getCreatedBy().getFullName(),
                b.getDecidedBy() == null ? null : b.getDecidedBy().getFullName(),
                b.getDecisionNote(),
                b.getCreatedAt(),
                b.getDecidedAt(),
                lines);
    }
}
