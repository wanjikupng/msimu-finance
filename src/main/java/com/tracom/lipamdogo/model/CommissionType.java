package com.tracom.lipamdogo.model;

/**
 * What a commission was earned for.
 */
public enum CommissionType {
    /** Agent's share of an M-PESA collection they sent from their terminal. */
    AGENT_COLLECTION,
    /** Merchant's override on collections made at its terminals. */
    MERCHANT_COLLECTION,
    /** Merchant's fixed fee for a phone financed from its stock. */
    MERCHANT_SALE
}
