package com.tracom.lipamdogo.model;

/**
 * Repayment cadence snapshot on a financing contract. Phase 3 supports DAILY
 * only (matching the existing payment-plan model); WEEKLY/MONTHLY exist so the
 * future installment redesign can extend without a schema change.
 */
public enum PaymentFrequency {
    DAILY,
    WEEKLY,
    MONTHLY
}
