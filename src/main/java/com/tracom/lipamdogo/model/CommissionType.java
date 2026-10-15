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
    MERCHANT_SALE,
    /**
     * M-PESA pays whole shillings: the cents left after rounding a payout down
     * are taken out of this settlement (negative) and carried into the next
     * one (positive), so nothing is lost.
     */
    ROUNDING_CARRY
}
