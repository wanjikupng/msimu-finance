package com.tracom.lipamdogo.model;

/**
 * Lifecycle of a single allocation row. Rows are never deleted: reversal
 * marks them REVERSED so the full application history stays auditable.
 */
public enum AllocationStatus {
    ACTIVE,
    REVERSED
}
