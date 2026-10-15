package com.tracom.lipamdogo.payment;

import com.tracom.lipamdogo.dto.ProviderFeedImportRequest;
import com.tracom.lipamdogo.dto.ProviderFeedImportResponse;
import com.tracom.lipamdogo.dto.ProviderFeedItem;
import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
import com.tracom.lipamdogo.model.ProviderTransactionStatus;
import com.tracom.lipamdogo.repository.PaymentTransactionRepository;
import com.tracom.lipamdogo.service.ReconciliationService;
import com.tracom.lipamdogo.support.SystemTime;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Produces the statement the (mock) M-PESA provider would send for the
 * transactions it handled, and imports it as provider evidence.
 *
 * <p>With a live integration this file would arrive from Safaricom (a
 * settlement report or the transaction-status API). In the prototype the mock
 * adapter has no storage of its own, so the statement is derived from the
 * provider references our adapter recorded. Optionally it injects two
 * realistic exceptions so reconciliation has something to catch: a provider
 * amount that differs from ours, and a paybill payment that never reached our
 * system (wrong account number).</p>
 *
 * <p>Importing is idempotent — lines already imported are skipped — and, like
 * every reconciliation input, it never changes a balance.</p>
 */
@Service
public class ProviderStatementSimulator {

    private final PaymentTransactionRepository transactionRepository;
    private final ReconciliationService reconciliationService;

    public ProviderStatementSimulator(PaymentTransactionRepository transactionRepository,
                                      ReconciliationService reconciliationService) {
        this.transactionRepository = transactionRepository;
        this.reconciliationService = reconciliationService;
    }

    public ProviderFeedImportResponse importMpesaStatement(boolean injectExceptions) {
        List<PaymentTransaction> handled = transactionRepository.findAll().stream()
                .filter(t -> t.getChannel() == PaymentChannel.MPESA && t.getProviderReference() != null)
                .sorted(Comparator.comparing(PaymentTransaction::getId))
                .toList();

        PaymentTransaction amountException = null;
        if (injectExceptions) {
            amountException = handled.stream()
                    .filter(t -> t.getStatus() == PaymentTransactionStatus.POSTED
                            && t.getAmount().compareTo(BigDecimal.valueOf(50)) > 0)
                    .reduce((first, second) -> second)
                    .orElse(null);
        }

        List<ProviderFeedItem> lines = new ArrayList<>();
        for (PaymentTransaction t : handled) {
            BigDecimal amount = t.getAmount();
            String note = "Simulated M-PESA statement line";
            if (t == amountException) {
                amount = amount.subtract(BigDecimal.TEN);
                note = "Simulated exception: provider settled a different amount";
            }
            lines.add(new ProviderFeedItem(PaymentChannel.MPESA, t.getProviderReference(), amount, "KES",
                    t.getCompletedAt() != null ? t.getCompletedAt() : t.getRequestedAt(),
                    providerStatusOf(t.getStatus()), note));
        }
        if (injectExceptions) {
            String unknown = "MPESA-PAYBILL-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 8).toUpperCase();
            LocalDateTime when = SystemTime.now().minusHours(3);
            lines.add(new ProviderFeedItem(PaymentChannel.MPESA, unknown, new BigDecimal("350.00"), "KES", when,
                    ProviderTransactionStatus.SUCCESS,
                    "Simulated exception: paybill payment with an unknown account number"));
        }
        if (lines.isEmpty()) {
            return new ProviderFeedImportResponse(0, 0, 0);
        }
        return reconciliationService.importFeed(new ProviderFeedImportRequest(lines));
    }

    private static ProviderTransactionStatus providerStatusOf(PaymentTransactionStatus internal) {
        return switch (internal) {
            case SUCCESS, POSTED -> ProviderTransactionStatus.SUCCESS;
            case FAILED -> ProviderTransactionStatus.FAILED;
            case REVERSED -> ProviderTransactionStatus.REVERSED;
            case INITIATED, PENDING -> ProviderTransactionStatus.PENDING;
        };
    }
}
