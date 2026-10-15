package com.tracom.lipamdogo.model;

/**
 * Provider-side transaction state as reported by an external feed.
 * This is what the provider <em>says</em> happened — it never drives
 * internal postings by itself.
 */
public enum ProviderTransactionStatus {
    SUCCESS,
    FAILED,
    PENDING,
    REVERSED
}
