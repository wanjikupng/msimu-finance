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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The shared secret between the platform and one financed device, created
 * when the contract is approved and the device handed over.
 *
 * <p>The device holds the same key in its lock agent and uses it to verify
 * unlock codes offline. In this prototype the key is stored in the database;
 * a production deployment would keep it in a hardware security module.</p>
 */
@Entity
@Table(name = "device_lock_keys")
public class DeviceLockKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false, unique = true)
    private LoanContract contract;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    /** 256-bit key, hex encoded. */
    @Column(name = "secret_key", nullable = false, length = 64)
    private String secretKey;

    /** Day zero for the "unlocked until" offset carried in each code. */
    @Column(name = "anchor_date", nullable = false)
    private LocalDate anchorDate;

    @Column(name = "last_sequence", nullable = false)
    private int lastSequence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public DeviceLockKey() {
        this.createdAt = SystemTime.now();
    }

    public Long getId() {
        return id;
    }

    public LoanContract getContract() {
        return contract;
    }

    public void setContract(LoanContract contract) {
        this.contract = contract;
    }

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public LocalDate getAnchorDate() {
        return anchorDate;
    }

    public void setAnchorDate(LocalDate anchorDate) {
        this.anchorDate = anchorDate;
    }

    public int getLastSequence() {
        return lastSequence;
    }

    public void setLastSequence(int lastSequence) {
        this.lastSequence = lastSequence;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
