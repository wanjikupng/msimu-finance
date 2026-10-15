package com.tracom.lipamdogo.model;

/**
 * Payment-transaction lifecycle. SUCCESS and POSTED are deliberately distinct:
 * SUCCESS means the provider confirmed receipt of money; POSTED means the
 * internal accounting operation applied it to the contract exactly once.
 * Only POSTED transactions have financial effect.
 *
 * <ul>
 *   <li>INITIATED — created, adapter not yet invoked (transient).</li>
 *   <li>PENDING — provider accepted but has not confirmed (awaits result).</li>
 *   <li>SUCCESS — provider confirmed receipt; not yet applied.</li>
 *   <li>FAILED — provider declined/failed; terminal, no financial effect.</li>
 *   <li>POSTED — applied to the contract exactly once; immutable.</li>
 *   <li>REVERSED — a posted payment explicitly rolled back; terminal.</li>
 * </ul>
 */
public enum PaymentTransactionStatus {
    INITIATED,
    PENDING,
    SUCCESS,
    FAILED,
    POSTED,
    REVERSED
}
