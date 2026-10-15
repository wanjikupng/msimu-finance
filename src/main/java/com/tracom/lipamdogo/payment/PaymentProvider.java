package com.tracom.lipamdogo.payment;

import com.tracom.lipamdogo.model.PaymentChannel;

/**
 * Channel adapter contract. A real integration (M-PESA Daraja, card
 * processor, bank API) implements this interface; the engine never contains
 * provider-specific logic.
 */
public interface PaymentProvider {

    PaymentChannel channel();

    /**
     * Invokes the provider for an already-persisted INITIATED transaction.
     * Must be side-effect free toward the contract: it only reports the
     * provider outcome. Posting is a separate engine step.
     */
    ProviderResult initiate(ProviderPaymentRequest request);
}
