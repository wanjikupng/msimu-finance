package com.tracom.lipamdogo.model;

/**
 * Lifecycle of a reconciliation run. Runs execute synchronously in the
 * requesting thread (prototype scale); FAILED marks an unexpected engine
 * error, never a mismatch outcome.
 */
public enum ReconciliationBatchStatus {
    STARTED,
    PROCESSING,
    COMPLETED,
    FAILED
}
