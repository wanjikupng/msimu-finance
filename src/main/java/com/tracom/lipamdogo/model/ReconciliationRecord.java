package com.tracom.lipamdogo.model;

import com.tracom.lipamdogo.support.SystemTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Result of comparing one internal payment against provider evidence.
 * Either side may be absent (MISSING_* outcomes). Records are observations
 * only — creating or updating one never touches balances, statuses, or
 * devices. Re-runs update records in place, so history never duplicates.
 */
@Entity
@Table(name = "reconciliation_records")
public class ReconciliationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id")
    private PaymentTransaction paymentTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_transaction_id")
    private ProviderTransaction providerTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private ReconciliationBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconciliationStatus status;

    /**
     * provider amount minus internal amount. Negative means the provider
     * reports less than we recorded. Null when one side is absent.
     */
    @Column(name = "difference_amount")
    private BigDecimal differenceAmount;

    @Column
    private String reason;

    @Column(name = "reconciled_at", nullable = false)
    private LocalDateTime reconciledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ReconciliationRecord() {
        LocalDateTime now = SystemTime.now();
        this.reconciledAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PaymentTransaction getPaymentTransaction() {
        return paymentTransaction;
    }

    public void setPaymentTransaction(PaymentTransaction paymentTransaction) {
        this.paymentTransaction = paymentTransaction;
    }

    public ProviderTransaction getProviderTransaction() {
        return providerTransaction;
    }

    public void setProviderTransaction(ProviderTransaction providerTransaction) {
        this.providerTransaction = providerTransaction;
    }

    public ReconciliationBatch getBatch() {
        return batch;
    }

    public void setBatch(ReconciliationBatch batch) {
        this.batch = batch;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public void setStatus(ReconciliationStatus status) {
        this.status = status;
    }

    public BigDecimal getDifferenceAmount() {
        return differenceAmount;
    }

    public void setDifferenceAmount(BigDecimal differenceAmount) {
        this.differenceAmount = differenceAmount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getReconciledAt() {
        return reconciledAt;
    }

    public void setReconciledAt(LocalDateTime reconciledAt) {
        this.reconciledAt = reconciledAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
