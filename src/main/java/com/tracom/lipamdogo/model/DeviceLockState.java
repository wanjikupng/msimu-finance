package com.tracom.lipamdogo.model;

/**
 * What a financed device shows once it holds its latest unlock code.
 */
public enum DeviceLockState {
    /** Paid up to (at least) today. */
    UNLOCKED,
    /** An installment is late but the grace days have not run out yet. */
    GRACE,
    /** Grace exhausted: the device is locked until a payment brings it current. */
    LOCKED,
    /** Fully paid: permanently unlocked, the customer owns the device. */
    OWNED
}
