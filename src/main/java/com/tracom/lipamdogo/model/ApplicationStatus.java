package com.tracom.lipamdogo.model;

/**
 * Financing-application lifecycle. Describes the <em>request</em>, not the
 * financing itself — the contract has its own state machine
 * ({@link LoanStatus}).
 *
 * <ul>
 *   <li>SUBMITTED — customer filed the request; device is RESERVED.</li>
 *   <li>UNDER_REVIEW — a loan officer opened the file for decision.</li>
 *   <li>APPROVED — officer approved; the contract was created atomically.</li>
 *   <li>REJECTED — officer declined with a decision reason; device released.</li>
 *   <li>CANCELLED — customer withdrew an undecided application; released.</li>
 * </ul>
 */
public enum ApplicationStatus {
    SUBMITTED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
    CANCELLED
}
