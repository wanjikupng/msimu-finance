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
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One slice of a posted payment applied to a single day's obligation.
 *
 * <p>Concepts: the {@link PaymentTransaction} is the payment <em>event</em>;
 * these rows explain how the posted event was <em>applied</em> (oldest unpaid
 * day first); the {@link LoanContract} carries the resulting <em>current
 * financial state</em>. Rows are immutable financial history — reversal marks
 * them REVERSED (with {@code reversedAt}) instead of deleting them, and the
 * contract schedule is rebuilt by replaying the remaining ACTIVE rows.</p>
 */
@Entity
@Table(name = "payment_allocations",
        uniqueConstraints = @UniqueConstraint(name = "uq_allocation_tx_sequence",
                columnNames = {"payment_transaction_id", "sequence"}))
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id", nullable = false)
    private PaymentTransaction paymentTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false)
    private LoanContract contract;

    @Column(name = "allocation_date", nullable = false)
    private LocalDate allocationDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private Integer sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllocationStatus status = AllocationStatus.ACTIVE;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public PaymentAllocation() {
        this.createdAt = SystemTime.now();
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

    public LoanContract getContract() {
        return contract;
    }

    public void setContract(LoanContract contract) {
        this.contract = contract;
    }

    public LocalDate getAllocationDate() {
        return allocationDate;
    }

    public void setAllocationDate(LocalDate allocationDate) {
        this.allocationDate = allocationDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
        this.sequence = sequence;
    }

    public AllocationStatus getStatus() {
        return status;
    }

    public void setStatus(AllocationStatus status) {
        this.status = status;
    }

    public LocalDateTime getReversedAt() {
        return reversedAt;
    }

    public void setReversedAt(LocalDateTime reversedAt) {
        this.reversedAt = reversedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
