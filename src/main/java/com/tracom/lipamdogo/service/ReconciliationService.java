package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.ProviderFeedImportRequest;
import com.tracom.lipamdogo.dto.ProviderFeedImportResponse;
import com.tracom.lipamdogo.dto.ProviderFeedItem;
import com.tracom.lipamdogo.dto.ReconciliationBatchResponse;
import com.tracom.lipamdogo.dto.ReconciliationRecordResponse;
import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
import com.tracom.lipamdogo.model.ProviderTransaction;
import com.tracom.lipamdogo.model.ProviderTransactionStatus;
import com.tracom.lipamdogo.model.ReconciliationBatch;
import com.tracom.lipamdogo.model.ReconciliationBatchStatus;
import com.tracom.lipamdogo.model.ReconciliationRecord;
import com.tracom.lipamdogo.model.ReconciliationStatus;
import com.tracom.lipamdogo.repository.PaymentTransactionRepository;
import com.tracom.lipamdogo.repository.ProviderTransactionRepository;
import com.tracom.lipamdogo.repository.ReconciliationBatchRepository;
import com.tracom.lipamdogo.repository.ReconciliationRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares internal payment events against imported provider evidence.
 *
 * <p><strong>Operational control layer only.</strong> This service reads loan
 * and payment tables but never writes them: no balances, statuses, devices,
 * postings, or reversals are created or altered here. Mismatches are recorded
 * as exceptions for human investigation.</p>
 */
@Service
public class ReconciliationService {

    private final ProviderTransactionRepository providerTransactionRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ReconciliationBatchRepository batchRepository;
    private final ReconciliationRecordRepository recordRepository;

    public ReconciliationService(ProviderTransactionRepository providerTransactionRepository,
                                 PaymentTransactionRepository paymentTransactionRepository,
                                 ReconciliationBatchRepository batchRepository,
                                 ReconciliationRecordRepository recordRepository) {
        this.providerTransactionRepository = providerTransactionRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.batchRepository = batchRepository;
        this.recordRepository = recordRepository;
    }

    /**
     * Imports provider feed rows. Repeat imports are idempotent: a row
     * identical to an already-stored one (provider, reference, amount, time)
     * is skipped, never duplicated.
     */
    @Transactional
    public ProviderFeedImportResponse importFeed(ProviderFeedImportRequest request) {
        int inserted = 0;
        int skipped = 0;
        for (ProviderFeedItem item : request.transactions()) {
            String reference = item.providerReference().trim();
            boolean exists = providerTransactionRepository
                    .findFirstByProviderAndProviderReferenceAndAmountAndTransactionTime(
                            item.provider(), reference, item.amount(), item.transactionTime())
                    .isPresent();
            if (exists) {
                skipped++;
                continue;
            }
            ProviderTransaction row = new ProviderTransaction();
            row.setProvider(item.provider());
            row.setProviderReference(reference);
            row.setAmount(item.amount());
            row.setCurrency(item.currency() == null || item.currency().isBlank() ? "KES" : item.currency().trim());
            row.setTransactionTime(item.transactionTime());
            row.setStatus(item.status());
            row.setRawMetadata(item.rawMetadata());
            providerTransactionRepository.save(row);
            inserted++;
        }
        return new ProviderFeedImportResponse(request.transactions().size(), inserted, skipped);
    }

    /**
     * Runs reconciliation over a feed scope and returns the completed batch.
     * Synchronous at prototype scale. Re-running the same scope updates
     * records in place — never duplicates them and never touches finances.
     */
    public ReconciliationBatchResponse run(PaymentChannel provider, LocalDateTime windowStart, LocalDateTime windowEnd) {
        ReconciliationBatch batch = new ReconciliationBatch();
        batch.setProvider(provider);
        batch.setWindowStart(windowStart);
        batch.setWindowEnd(windowEnd);
        batch.setStatus(ReconciliationBatchStatus.STARTED);
        batch = batchRepository.saveAndFlush(batch);
        batch.setBatchNumber(number("RECON", batch.getId()));
        batch.setStatus(ReconciliationBatchStatus.PROCESSING);
        batchRepository.save(batch);

        try {
            long[] counts = reconcileScope(batch, provider, windowStart, windowEnd);
            batch.setTotalRecords(counts[0]);
            batch.setMatchedCount(counts[1]);
            batch.setExceptionCount(counts[0] - counts[1]);
            batch.setStatus(ReconciliationBatchStatus.COMPLETED);
            batch.setCompletedAt(LocalDateTime.now());
            batchRepository.save(batch);
        } catch (RuntimeException e) {
            batch.setStatus(ReconciliationBatchStatus.FAILED);
            batch.setCompletedAt(LocalDateTime.now());
            batchRepository.save(batch);
            throw e;
        }
        return ReconciliationBatchResponse.from(batch);
    }

    // NOTE: intentionally not @Transactional — this method is self-invoked
    // (proxies do not intercept self-calls), so each repository call commits
    // on its own. That is the desired behavior here: partial run results
    // persist even if the run dies mid-way, and FAILED batches still record.
    protected long[] reconcileScope(ReconciliationBatch batch, PaymentChannel provider,
                                    LocalDateTime windowStart, LocalDateTime windowEnd) {
        List<ProviderTransaction> feed = providerTransactionRepository.findInScope(provider, windowStart, windowEnd);
        long total = 0;
        long matched = 0;

        // Group by (provider, reference) in feed order; surplus rows are duplicates.
        Map<String, List<ProviderTransaction>> groups = new LinkedHashMap<>();
        for (ProviderTransaction row : feed) {
            groups.computeIfAbsent(row.getProvider() + "|" + row.getProviderReference(), k -> new ArrayList<>()).add(row);
        }

        java.util.Set<String> seenReferences = new java.util.HashSet<>();
        for (List<ProviderTransaction> group : groups.values()) {
            ProviderTransaction primary = group.get(0);
            seenReferences.add(primary.getProviderReference());
            if (group.size() > 1) {
                // The primary still reconciles normally; each surplus row is an exception.
                if (reconcileProviderRow(batch, primary)) {
                    matched++;
                }
                total++;
                for (int i = 1; i < group.size(); i++) {
                    saveRecord(batch, null, group.get(i), ReconciliationStatus.DUPLICATE_PROVIDER, null,
                            "Provider reference " + group.get(i).getProviderReference()
                                    + " appears " + group.size() + " times in the reconciled feed scope.");
                    total++;
                }
            } else {
                if (reconcileProviderRow(batch, primary)) {
                    matched++;
                }
                total++;
            }
        }

        // Internal transactions with a provider reference that the feed scope
        // does not contain are missing on the provider side.
        for (PaymentTransaction internal : paymentTransactionRepository.findAll()) {
            if (internal.getProviderReference() == null) {
                continue;
            }
            if (provider != null && internal.getChannel() != provider) {
                continue;
            }
            if (windowStart != null && (internal.getRequestedAt() == null || internal.getRequestedAt().isBefore(windowStart))) {
                continue;
            }
            if (windowEnd != null && (internal.getRequestedAt() == null || internal.getRequestedAt().isAfter(windowEnd))) {
                continue;
            }
            if (seenReferences.contains(internal.getProviderReference())) {
                continue;
            }
            saveRecord(batch, internal, null, ReconciliationStatus.MISSING_PROVIDER, null,
                    "Internal transaction " + internal.getTransactionReference()
                            + " has no matching record in the provider feed scope.");
            total++;
        }
        return new long[]{total, matched};
    }

    /**
     * Reconciles one primary provider row. Returns true when MATCHED.
     * Never writes to loan, payment, or device tables.
     */
    private boolean reconcileProviderRow(ReconciliationBatch batch, ProviderTransaction providerRow) {
        var internal = paymentTransactionRepository.findByProviderReference(providerRow.getProviderReference())
                .orElse(null);
        if (internal == null) {
            saveRecord(batch, null, providerRow, ReconciliationStatus.MISSING_INTERNAL, null,
                    "Provider reports " + providerRow.getProviderReference()
                            + " but no internal transaction carries that reference. No payment was created.");
            return false;
        }
        if (internal.getChannel() != null && internal.getChannel() != providerRow.getProvider()) {
            saveRecord(batch, internal, providerRow, ReconciliationStatus.STATUS_MISMATCH,
                    differenceOf(providerRow.getAmount(), internal.getAmount()),
                    "Channel mismatch: internal " + internal.getChannel() + " vs provider " + providerRow.getProvider() + ".");
            return false;
        }
        if (!statesCompatible(internal.getStatus(), providerRow.getStatus())) {
            saveRecord(batch, internal, providerRow, ReconciliationStatus.STATUS_MISMATCH,
                    differenceOf(providerRow.getAmount(), internal.getAmount()),
                    "State conflict: internal " + internal.getStatus() + " vs provider " + providerRow.getStatus() + ".");
            return false;
        }
        if (!currenciesAgree(internal, providerRow) || internal.getAmount().compareTo(providerRow.getAmount()) != 0) {
            saveRecord(batch, internal, providerRow, ReconciliationStatus.AMOUNT_MISMATCH,
                    differenceOf(providerRow.getAmount(), internal.getAmount()),
                    "Amount differs: internal KES " + internal.getAmount() + " vs provider "
                            + providerRow.getAmount() + " " + providerRow.getCurrency() + ".");
            return false;
        }
        saveRecord(batch, internal, providerRow, ReconciliationStatus.MATCHED, BigDecimal.ZERO, null);
        return true;
    }

    /**
     * State compatibility map. Only exact compatible pairs match; anything
     * else is a STATUS_MISMATCH for investigation — never auto-corrected.
     */
    private boolean statesCompatible(PaymentTransactionStatus internal, ProviderTransactionStatus provider) {
        return switch (provider) {
            case SUCCESS -> internal == PaymentTransactionStatus.SUCCESS || internal == PaymentTransactionStatus.POSTED;
            case FAILED -> internal == PaymentTransactionStatus.FAILED;
            case PENDING -> internal == PaymentTransactionStatus.INITIATED || internal == PaymentTransactionStatus.PENDING;
            case REVERSED -> internal == PaymentTransactionStatus.REVERSED;
        };
    }

    private boolean currenciesAgree(PaymentTransaction internal, ProviderTransaction providerRow) {
        // Internal amounts are KES; a differing provider currency is an amount
        // exception, not a silent conversion.
        return providerRow.getCurrency() == null || "KES".equalsIgnoreCase(providerRow.getCurrency());
    }

    /** Sign convention: provider amount minus internal amount. */
    private BigDecimal differenceOf(BigDecimal providerAmount, BigDecimal internalAmount) {
        if (providerAmount == null || internalAmount == null) {
            return null;
        }
        return providerAmount.subtract(internalAmount);
    }

    /**
     * Idempotent record write. One primary record per internal transaction and
     * one per provider row; re-runs update in place (including status
     * transitions such as MISSING_PROVIDER → MATCHED) so history never
     * duplicates while always reflecting the latest covering run.
     */
    private void saveRecord(ReconciliationBatch batch, PaymentTransaction internal, ProviderTransaction providerRow,
                            ReconciliationStatus status, BigDecimal difference, String reason) {
        ReconciliationRecord record = null;
        if (internal != null) {
            record = recordRepository.findFirstByPaymentTransactionOrderByIdDesc(internal).orElse(null);
        }
        if (record == null && providerRow != null) {
            record = recordRepository.findFirstByProviderTransactionOrderByIdDesc(providerRow).orElse(null);
            if (record != null && record.getPaymentTransaction() != null && internal != null
                    && !record.getPaymentTransaction().getId().equals(internal.getId())) {
                // Row already belongs to a different internal transaction
                // (e.g. a duplicate reference): keep it, create a separate one.
                record = null;
            }
        }
        if (record == null) {
            record = new ReconciliationRecord();
            record.setPaymentTransaction(internal);
            record.setProviderTransaction(providerRow);
        } else {
            if (internal != null && (record.getPaymentTransaction() == null
                    || !record.getPaymentTransaction().getId().equals(internal.getId()))) {
                record.setPaymentTransaction(internal);
            }
            if (providerRow != null && (record.getProviderTransaction() == null
                    || !record.getProviderTransaction().getId().equals(providerRow.getId()))) {
                record.setProviderTransaction(providerRow);
            }
        }
        record.setBatch(batch);
        record.setStatus(status);
        record.setDifferenceAmount(difference);
        record.setReason(reason);
        record.setReconciledAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        recordRepository.save(record);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationBatchResponse> listBatches() {
        return batchRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .map(ReconciliationBatchResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReconciliationBatchResponse getBatch(Long id) {
        return ReconciliationBatchResponse.from(batchRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reconciliation batch not found.")));
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRecordResponse> listRecords(Long batchId, ReconciliationStatus status) {
        List<ReconciliationRecord> records;
        if (batchId != null && status != null) {
            records = recordRepository.findByBatchIdAndStatus(batchId, status);
        } else if (batchId != null) {
            records = recordRepository.findByBatchId(batchId);
        } else if (status != null) {
            records = recordRepository.findByStatus(status);
        } else {
            records = recordRepository.findAll();
        }
        return records.stream().map(ReconciliationRecordResponse::from).toList();
    }

    private String number(String prefix, Long id) {
        return "%s-%d-%06d".formatted(prefix, LocalDate.now().getYear(), id);
    }
}
