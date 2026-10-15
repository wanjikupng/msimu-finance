package com.tracom.lipamdogo.model;

/**
 * Payment channel for a transaction. Only MPESA has a working provider
 * adapter in Phase 4; the remaining values exist as domain vocabulary for
 * future providers. Initiation on a channel without an adapter is rejected.
 */
public enum PaymentChannel {
    MPESA,
    CARD,
    BANK,
    AGENT,
    POS,
    USSD
}
