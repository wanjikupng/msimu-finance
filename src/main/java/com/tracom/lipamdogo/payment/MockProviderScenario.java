package com.tracom.lipamdogo.payment;

/**
 * Simulation control honored ONLY by mock providers. A real provider adapter
 * ignores this field entirely. AUTO lets the mock decide deterministically
 * (documented in MockMpesaPaymentProvider); the explicit values exist so the
 * academy demo and tests can exercise PENDING and FAILED paths without any
 * real money movement. This is a simulation knob, never a status override:
 * the transaction still flows through the full lifecycle.
 */
public enum MockProviderScenario {
    AUTO,
    SUCCESS,
    PENDING,
    FAILED
}
