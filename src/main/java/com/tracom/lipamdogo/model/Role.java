package com.tracom.lipamdogo.model;

/**
 * Strongly typed platform roles.
 *
 * <p>New roles can be added here without touching the database schema, since the
 * role is persisted as the enum name. Update {@link #isStaff()} and the relevant
 * authorization checks when a new role needs staff-level access.</p>
 */
public enum Role {
    ADMIN,
    LOAN_OFFICER,
    PAYMENTS_OPERATOR,
    AGENT,
    MERCHANT,
    TECHNICIAN,
    CUSTOMER;

    /**
     * Staff roles that may operate across customers (loans, contracts, customers).
     * AGENT/MERCHANT/TECHNICIAN/CUSTOMER are intentionally excluded for Phase 1:
     * their cross-customer scoping (assigned customers, own transactions) has no
     * backing data model yet and will be enforced in later phases.
     */
    public boolean isStaff() {
        return this == ADMIN || this == LOAN_OFFICER || this == PAYMENTS_OPERATOR;
    }
}
