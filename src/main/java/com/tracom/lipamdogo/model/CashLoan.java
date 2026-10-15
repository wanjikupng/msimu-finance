package com.tracom.lipamdogo.model;

import com.tracom.lipamdogo.support.SystemTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cash_loans")
public class CashLoan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Single authoritative identity: every cash loan belongs to exactly one
    // UserAccount row. Never join financial records by customer name.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private UserAccount customer;

    @Column(name = "amount_requested", nullable = false)
    private BigDecimal amountRequested;

    @Column(name = "approved_amount", nullable = false)
    private BigDecimal approvedAmount;

    @Column(name = "balance_remaining", nullable = false)
    private BigDecimal balanceRemaining;

    @Column(name = "monthly_rate", nullable = false)
    private BigDecimal monthlyRate;

    @Column(name = "purpose")
    private String purpose;

    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public CashLoan() {
        this.createdAt = SystemTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UserAccount getCustomer() {
        return customer;
    }

    public void setCustomer(UserAccount customer) {
        this.customer = customer;
    }

    public BigDecimal getAmountRequested() {
        return amountRequested;
    }

    public void setAmountRequested(BigDecimal amountRequested) {
        this.amountRequested = amountRequested;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public void setApprovedAmount(BigDecimal approvedAmount) {
        this.approvedAmount = approvedAmount;
    }

    public BigDecimal getBalanceRemaining() {
        return balanceRemaining;
    }

    public void setBalanceRemaining(BigDecimal balanceRemaining) {
        this.balanceRemaining = balanceRemaining;
    }

    public BigDecimal getMonthlyRate() {
        return monthlyRate;
    }

    public void setMonthlyRate(BigDecimal monthlyRate) {
        this.monthlyRate = monthlyRate;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
