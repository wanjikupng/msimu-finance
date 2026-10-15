package com.tracom.lipamdogo.model;

/**
 * Why an unlock code was issued.
 */
public enum UnlockCodeReason {
    /** First code, issued when the device is handed over on approval. */
    PROVISIONED,
    /** A posted payment moved the paid-through date. */
    PAYMENT,
    /** A reversal moved the paid-through date back. */
    REVERSAL,
    /** The contract is fully paid: the device unlocks for good. */
    PAID_OFF
}
