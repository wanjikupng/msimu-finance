package com.tracom.lipamdogo.payment;

import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.repository.PaymentTransactionRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Simulated M-PESA adapter for the academy prototype. Performs no network
 * I/O, holds no credentials, and moves no real money. NEVER present this as
 * actual Safaricom processing.
 *
 * <p>Deterministic behavior (no randomness, fully testable):</p>
 * <ul>
 *   <li>explicit {@code mockScenario} (SUCCESS/PENDING/FAILED) is honored;</li>
 *   <li>AUTO → SUCCESS. Deferred/failed paths are exercised through the
 *       explicit scenario, keeping the default demo path (including large
 *       final-balance payments) synchronous and predictable.</li>
 * </ul>
 */
@Component
public class MockMpesaPaymentProvider implements PaymentProvider {

    private final PaymentTransactionRepository transactionRepository;

    public MockMpesaPaymentProvider(PaymentTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public PaymentChannel channel() {
        return PaymentChannel.MPESA;
    }

    @Override
    public ProviderResult initiate(ProviderPaymentRequest request) {
        MockProviderScenario scenario = request.mockScenario() == null
                ? MockProviderScenario.AUTO
                : request.mockScenario();
        String providerReference = generateProviderReference();

        return switch (scenario) {
            case SUCCESS, AUTO -> ProviderResult.success(providerReference);
            case FAILED -> ProviderResult.failed(providerReference,
                    "Simulated provider decline: insufficient funds (mock).");
            case PENDING -> ProviderResult.pending(providerReference);
        };
    }

    private String generateProviderReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = "MPESA-MOCK-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 8).toUpperCase();
            if (transactionRepository.findByProviderReference(reference).isEmpty()) {
                return reference;
            }
        }
        throw new IllegalStateException("Could not generate a unique provider reference.");
    }
}
