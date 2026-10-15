package com.tracom.lipamdogo.model;

/**
 * Outcome of comparing one internal payment against provider evidence.
 *
 * <ul>
 *   <li>MATCHED — internal and provider records agree on state and amount.</li>
 *   <li>AMOUNT_MISMATCH — states compatible, amounts differ.</li>
 *   <li>STATUS_MISMATCH — provider state conflicts with internal state.</li>
 *   <li>MISSING_INTERNAL — provider reports a reference we cannot identify.</li>
 *   <li>MISSING_PROVIDER — our transaction is absent from the provider feed.</li>
 *   <li>DUPLICATE_PROVIDER — the same provider reference appears more than
 *       once in the reconciled feed scope.</li>
 * </ul>
 *
 * <p>Every non-MATCHED outcome is an operational exception for investigation.
 * None of them alters financial state.</p>
 */
public enum ReconciliationStatus {
    MATCHED,
    AMOUNT_MISMATCH,
    STATUS_MISMATCH,
    MISSING_INTERNAL,
    MISSING_PROVIDER,
    DUPLICATE_PROVIDER
}
