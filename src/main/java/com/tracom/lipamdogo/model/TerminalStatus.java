package com.tracom.lipamdogo.model;

/**
 * POS terminal lifecycle in the terminal management system.
 */
public enum TerminalStatus {
    /** In service; usable by its assigned agent. */
    ACTIVE,
    /** Temporarily blocked by staff (e.g. agent under review). */
    SUSPENDED,
    /** Reported faulty; out of service until repaired. */
    FAULTY,
    /** Permanently withdrawn. Terminal. */
    RETIRED
}
