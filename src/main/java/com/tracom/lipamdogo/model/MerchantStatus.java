package com.tracom.lipamdogo.model;

/**
 * Business standing of a {@link Merchant}.
 *
 * <ul>
 *   <li>PENDING — registered, not yet approved to operate.</li>
 *   <li>ACTIVE — approved and operating.</li>
 *   <li>SUSPENDED — temporarily blocked (devices stay put; new assignments stop).</li>
 *   <li>CLOSED — permanently offboarded.</li>
 * </ul>
 */
public enum MerchantStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    CLOSED
}
