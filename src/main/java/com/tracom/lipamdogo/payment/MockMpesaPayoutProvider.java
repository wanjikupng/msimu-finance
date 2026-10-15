package com.tracom.lipamdogo.payment;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Simulated M-PESA payouts for commission settlement: B2C to an agent's
 * phone, B2B to a merchant. A live integration would call Safaricom's B2C /
 * B2B APIs and confirm on their result callback; this mock answers at once.
 * A payee with no valid M-PESA number fails, as the real API would.
 */
@Component
public class MockMpesaPayoutProvider {

    public record PayoutResult(boolean success, String reference, String failureReason) {
    }

    public PayoutResult send(String method, String msisdn, BigDecimal amount) {
        if (msisdn == null || msisdn.isBlank()) {
            return new PayoutResult(false, null, "No valid M-PESA number on file for this payee.");
        }
        if (amount == null || amount.signum() <= 0) {
            return new PayoutResult(false, null, "Payout amount must be positive.");
        }
        String prefix = "MPESA_B2B".equals(method) ? "MPESA-B2B-" : "MPESA-B2C-";
        return new PayoutResult(true,
                prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(), null);
    }
}
