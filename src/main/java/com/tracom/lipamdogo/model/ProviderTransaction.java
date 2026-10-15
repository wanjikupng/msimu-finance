package com.tracom.lipamdogo.model;

import com.tracom.lipamdogo.support.SystemTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * External/provider transaction evidence, imported from a (simulated)
 * provider feed. Contains only provider-side information — no customer,
 * contract, device, or receipt data is duplicated here; those link through
 * reconciliation against {@link PaymentTransaction}.
 */
@Entity
@Table(name = "provider_transactions")
public class ProviderTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentChannel provider;

    @Column(name = "provider_reference", nullable = false)
    private String providerReference;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "transaction_time", nullable = false)
    private LocalDateTime transactionTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderTransactionStatus status;

    @Column(name = "raw_metadata")
    private String rawMetadata;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    public ProviderTransaction() {
        this.importedAt = SystemTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PaymentChannel getProvider() {
        return provider;
    }

    public void setProvider(PaymentChannel provider) {
        this.provider = provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDateTime getTransactionTime() {
        return transactionTime;
    }

    public void setTransactionTime(LocalDateTime transactionTime) {
        this.transactionTime = transactionTime;
    }

    public ProviderTransactionStatus getStatus() {
        return status;
    }

    public void setStatus(ProviderTransactionStatus status) {
        this.status = status;
    }

    public String getRawMetadata() {
        return rawMetadata;
    }

    public void setRawMetadata(String rawMetadata) {
        this.rawMetadata = rawMetadata;
    }

    public LocalDateTime getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(LocalDateTime importedAt) {
        this.importedAt = importedAt;
    }
}
