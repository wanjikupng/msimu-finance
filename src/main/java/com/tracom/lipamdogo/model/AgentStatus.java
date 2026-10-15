package com.tracom.lipamdogo.model;

/**
 * Working standing of an {@link Agent} profile.
 *
 * <ul>
 *   <li>PENDING — created, not yet approved to operate.</li>
 *   <li>ACTIVE — approved and operating under their merchant.</li>
 *   <li>SUSPENDED — temporarily blocked from new customer/device workflows.</li>
 *   <li>TERMINATED — permanently offboarded.</li>
 * </ul>
 */
public enum AgentStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    TERMINATED
}
