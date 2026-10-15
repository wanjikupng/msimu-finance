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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One issued unlock code. Codes are an append-only history: a new code
 * supersedes the previous one on the device (higher sequence), it never edits it.
 */
@Entity
@Table(name = "unlock_codes")
public class UnlockCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lock_key_id", nullable = false)
    private DeviceLockKey lockKey;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    /** Last day the device stays unlocked; null when permanent. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "permanent", nullable = false)
    private boolean permanent;

    @Column(name = "code", nullable = false, length = 12)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private UnlockCodeReason reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id")
    private PaymentTransaction paymentTransaction;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    public UnlockCode() {
        this.issuedAt = SystemTime.now();
    }

    public Long getId() {
        return id;
    }

    public DeviceLockKey getLockKey() {
        return lockKey;
    }

    public void setLockKey(DeviceLockKey lockKey) {
        this.lockKey = lockKey;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDate validUntil) {
        this.validUntil = validUntil;
    }

    public boolean isPermanent() {
        return permanent;
    }

    public void setPermanent(boolean permanent) {
        this.permanent = permanent;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public UnlockCodeReason getReason() {
        return reason;
    }

    public void setReason(UnlockCodeReason reason) {
        this.reason = reason;
    }

    public PaymentTransaction getPaymentTransaction() {
        return paymentTransaction;
    }

    public void setPaymentTransaction(PaymentTransaction paymentTransaction) {
        this.paymentTransaction = paymentTransaction;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }
}
