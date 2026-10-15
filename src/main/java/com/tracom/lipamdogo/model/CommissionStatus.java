package com.tracom.lipamdogo.model;

/**
 * Where a commission entry is in the payout cycle.
 */
public enum CommissionStatus {
    /** Earned (or clawed back) and waiting for the next settlement run. */
    ACCRUED,
    /** Included in a settlement batch that is awaiting approval or payout. */
    IN_SETTLEMENT,
    /** Paid out over M-PESA. */
    SETTLED
}
