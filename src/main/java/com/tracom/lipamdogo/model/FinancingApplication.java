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
 * A customer's request to finance one physical device. Approval of this
 * request is the ONLY path to a live {@link LoanContract} — customers can
 * never create contracts directly.
 *
 * <p>Requested terms are locked at submission (device price snapshot, deposit,
 * principal, plan reference) so the officer reviews exactly what the customer
 * asked for, and later catalogue changes cannot silently alter the deal.</p>
 */
@Entity
@Table(name = "financing_applications")
public class FinancingApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_number", unique = true)
    private String applicationNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private UserAccount customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status = ApplicationStatus.SUBMITTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_plan_id", nullable = false)
    private PaymentPlan paymentPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_mode_id")
    private PaymentMode paymentMode;

    @Column(name = "device_price", nullable = false)
    private BigDecimal devicePrice;

    @Column(name = "deposit_amount", nullable = false)
    private BigDecimal depositAmount;

    @Column(name = "requested_principal", nullable = false)
    private BigDecimal requestedPrincipal;

    @Column(name = "approved_principal")
    private BigDecimal approvedPrincipal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private UserAccount reviewedBy;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public FinancingApplication() {
        this.submittedAt = SystemTime.now();
        this.createdAt = SystemTime.now();
        this.updatedAt = SystemTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getApplicationNumber() {
        return applicationNumber;
    }

    public void setApplicationNumber(String applicationNumber) {
        this.applicationNumber = applicationNumber;
    }

    public UserAccount getCustomer() {
        return customer;
    }

    public void setCustomer(UserAccount customer) {
        this.customer = customer;
    }

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    public PaymentPlan getPaymentPlan() {
        return paymentPlan;
    }

    public void setPaymentPlan(PaymentPlan paymentPlan) {
        this.paymentPlan = paymentPlan;
    }

    public PaymentMode getPaymentMode() {
        return paymentMode;
    }

    public void setPaymentMode(PaymentMode paymentMode) {
        this.paymentMode = paymentMode;
    }

    public BigDecimal getDevicePrice() {
        return devicePrice;
    }

    public void setDevicePrice(BigDecimal devicePrice) {
        this.devicePrice = devicePrice;
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public void setDepositAmount(BigDecimal depositAmount) {
        this.depositAmount = depositAmount;
    }

    public BigDecimal getRequestedPrincipal() {
        return requestedPrincipal;
    }

    public void setRequestedPrincipal(BigDecimal requestedPrincipal) {
        this.requestedPrincipal = requestedPrincipal;
    }

    public BigDecimal getApprovedPrincipal() {
        return approvedPrincipal;
    }

    public void setApprovedPrincipal(BigDecimal approvedPrincipal) {
        this.approvedPrincipal = approvedPrincipal;
    }

    public UserAccount getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(UserAccount reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
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
