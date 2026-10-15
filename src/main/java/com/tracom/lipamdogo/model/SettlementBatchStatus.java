package com.tracom.lipamdogo.model;

/**
 * Settlement batch lifecycle. A batch is prepared by one staff member and
 * must be approved by a different one before any money leaves.
 */
public enum SettlementBatchStatus {
    PENDING_APPROVAL,
    /** Every payout line succeeded. */
    PAID,
    /** Some lines failed; their commissions return to ACCRUED for the next run. */
    PARTIALLY_PAID,
    /** Rejected by the approver; all commissions return to ACCRUED. */
    REJECTED
}
