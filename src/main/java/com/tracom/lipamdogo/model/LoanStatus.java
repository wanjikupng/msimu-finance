package com.tracom.lipamdogo.model;

/**
 * Financing state machine for a {@link LoanContract}.
 *
 * <p>This describes the <em>loan</em>, not the physical asset. The physical
 * device has its own lifecycle ({@link DeviceStatus}); the two state machines
 * are deliberately not hardwired together.</p>
 */
public enum LoanStatus {
    ACTIVE,
    OVERDUE,
    PAID_OFF
}
