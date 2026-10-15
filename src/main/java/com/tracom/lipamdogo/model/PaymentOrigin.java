package com.tracom.lipamdogo.model;

/**
 * Where a payment was started. The money always moves over M-PESA; the
 * origin only records the touchpoint.
 */
public enum PaymentOrigin {
    /** The customer paid from their own portal / phone. */
    CUSTOMER_APP,
    /** An agent sent an M-PESA prompt to the customer from a POS terminal. */
    AGENT_TERMINAL,
    /** Staff initiated it from the back office. */
    BACK_OFFICE
}
