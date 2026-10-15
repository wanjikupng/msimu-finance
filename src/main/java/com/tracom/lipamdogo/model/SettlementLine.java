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
 * One payout in a settlement batch: the net commission owed to one agent
 * (paid by M-PESA B2C to their phone) or one merchant (M-PESA B2B).
 */
@Entity
@Table(name = "settlement_lines")
public class SettlementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private SettlementBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_type", nullable = false)
    private BeneficiaryType beneficiaryType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @Column(name = "payee_name", nullable = false)
    private String payeeName;

    @Column(name = "payee_msisdn")
    private String payeeMsisdn;

    @Column(name = "payout_method", nullable = false)
    private String payoutMethod;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "earned_amount", nullable = false)
    private BigDecimal earnedAmount;

    @Column(name = "clawback_amount", nullable = false)
    private BigDecimal clawbackAmount;

    @Column(name = "net_amount", nullable = false)
    private BigDecimal netAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SettlementLineStatus status;

    @Column(name = "payout_reference")
    private String payoutReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    public SettlementLine() {
    }

    public Long getId() {
        return id;
    }

    public SettlementBatch getBatch() {
        return batch;
    }

    public void setBatch(SettlementBatch batch) {
        this.batch = batch;
    }

    public BeneficiaryType getBeneficiaryType() {
        return beneficiaryType;
    }

    public void setBeneficiaryType(BeneficiaryType beneficiaryType) {
        this.beneficiaryType = beneficiaryType;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public void setMerchant(Merchant merchant) {
        this.merchant = merchant;
    }

    public String getPayeeName() {
        return payeeName;
    }

    public void setPayeeName(String payeeName) {
        this.payeeName = payeeName;
    }

    public String getPayeeMsisdn() {
        return payeeMsisdn;
    }

    public void setPayeeMsisdn(String payeeMsisdn) {
        this.payeeMsisdn = payeeMsisdn;
    }

    public String getPayoutMethod() {
        return payoutMethod;
    }

    public void setPayoutMethod(String payoutMethod) {
        this.payoutMethod = payoutMethod;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public void setEntryCount(int entryCount) {
        this.entryCount = entryCount;
    }

    public BigDecimal getEarnedAmount() {
        return earnedAmount;
    }

    public void setEarnedAmount(BigDecimal earnedAmount) {
        this.earnedAmount = earnedAmount;
    }

    public BigDecimal getClawbackAmount() {
        return clawbackAmount;
    }

    public void setClawbackAmount(BigDecimal clawbackAmount) {
        this.clawbackAmount = clawbackAmount;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(BigDecimal netAmount) {
        this.netAmount = netAmount;
    }

    public SettlementLineStatus getStatus() {
        return status;
    }

    public void setStatus(SettlementLineStatus status) {
        this.status = status;
    }

    public String getPayoutReference() {
        return payoutReference;
    }

    public void setPayoutReference(String payoutReference) {
        this.payoutReference = payoutReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }
}
