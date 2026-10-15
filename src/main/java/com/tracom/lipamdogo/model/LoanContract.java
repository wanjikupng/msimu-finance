package com.tracom.lipamdogo.model;

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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "loan_contracts")
public class LoanContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private UserAccount customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Physical device financed under this contract. Nullable in Phase 2:
     * populated by the future financing-approval workflow (Phase 3), which
     * links an approved application to one concrete asset.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    /**
     * The approved application this contract was created from. Set for every
     * contract created through the Phase 3 approval workflow.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id")
    private FinancingApplication application;

    @Column(name = "contract_number", unique = true)
    private String contractNumber;

    @Column(name = "deposit_paid", nullable = false)
    private BigDecimal depositPaid;

    @Column(name = "amount_remaining", nullable = false)
    private BigDecimal amountRemaining;

    /**
     * Authoritative balance trio (Phase 4). totalPayable is the original
     * obligation; amountPaid is the cumulative POSTED total; amountRemaining
     * is always recomputed as totalPayable − amountPaid by the posting
     * service — the ONLY writer of these fields. Invariant:
     * amountPaid + amountRemaining = totalPayable.
     */
    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    /**
     * Amount posted toward the installment due on nextPaymentDueDate.
     * Partial payments accumulate here; covering the installment advances the
     * due date. Never silently discarded: every posted shilling counts.
     */
    @Column(name = "installment_paid", nullable = false)
    private BigDecimal installmentPaid = BigDecimal.ZERO;

    @Column(name = "daily_amount", nullable = false)
    private BigDecimal dailyAmount;

    /**
     * Approved financing-terms snapshot. Copied from the application at
     * approval time so later catalogue/plan changes can never silently alter
     * a live contract. (dailyAmount above is the installment snapshot.)
     */
    @Column(name = "principal_amount")
    private BigDecimal principalAmount;

    @Column(name = "total_payable")
    private BigDecimal totalPayable;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_frequency")
    private PaymentFrequency paymentFrequency = PaymentFrequency.DAILY;

    @Column(name = "device_price")
    private BigDecimal devicePrice;

    @Column(name = "plan_name")
    private String planName;

    @Column(name = "plan_duration_days")
    private Integer planDurationDays;

    // Financing state machine. Deliberately separate from DeviceStatus
    // (the physical-asset lifecycle); the two are not hardwired together.
    @Column(name = "loan_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private LoanStatus status = LoanStatus.ACTIVE;

    @Column(name = "next_payment_due_date")
    private LocalDate nextPaymentDueDate;

    /**
     * Schedule-replay anchor: the due date before any payment was ever
     * posted (set once at approval). Reversal rebuilds the installment
     * schedule by replaying ACTIVE allocation rows forward from this date,
     * which reproduces exactly the state as if reversed payments never posted.
     */
    @Column(name = "schedule_anchor_date", nullable = false)
    private LocalDate scheduleAnchorDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public LoanContract() {
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

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    public FinancingApplication getApplication() {
        return application;
    }

    public void setApplication(FinancingApplication application) {
        this.application = application;
    }

    public String getContractNumber() {
        return contractNumber;
    }

    public void setContractNumber(String contractNumber) {
        this.contractNumber = contractNumber;
    }

    public BigDecimal getDepositPaid() {
        return depositPaid;
    }

    public void setDepositPaid(BigDecimal depositPaid) {
        this.depositPaid = depositPaid;
    }

    public BigDecimal getAmountRemaining() {
        return amountRemaining;
    }

    public void setAmountRemaining(BigDecimal amountRemaining) {
        this.amountRemaining = amountRemaining;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public void setAmountPaid(BigDecimal amountPaid) {
        this.amountPaid = amountPaid;
    }

    public BigDecimal getInstallmentPaid() {
        return installmentPaid;
    }

    public void setInstallmentPaid(BigDecimal installmentPaid) {
        this.installmentPaid = installmentPaid;
    }

    public BigDecimal getDailyAmount() {
        return dailyAmount;
    }

    public void setDailyAmount(BigDecimal dailyAmount) {
        this.dailyAmount = dailyAmount;
    }

    public BigDecimal getPrincipalAmount() {
        return principalAmount;
    }

    public void setPrincipalAmount(BigDecimal principalAmount) {
        this.principalAmount = principalAmount;
    }

    public BigDecimal getTotalPayable() {
        return totalPayable;
    }

    public void setTotalPayable(BigDecimal totalPayable) {
        this.totalPayable = totalPayable;
    }

    public PaymentFrequency getPaymentFrequency() {
        return paymentFrequency;
    }

    public void setPaymentFrequency(PaymentFrequency paymentFrequency) {
        this.paymentFrequency = paymentFrequency;
    }

    public BigDecimal getDevicePrice() {
        return devicePrice;
    }

    public void setDevicePrice(BigDecimal devicePrice) {
        this.devicePrice = devicePrice;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public Integer getPlanDurationDays() {
        return planDurationDays;
    }

    public void setPlanDurationDays(Integer planDurationDays) {
        this.planDurationDays = planDurationDays;
    }

    public LoanStatus getStatus() {
        return status;
    }

    public void setStatus(LoanStatus status) {
        this.status = status;
    }

    public LocalDate getNextPaymentDueDate() {
        return nextPaymentDueDate;
    }

    public void setNextPaymentDueDate(LocalDate nextPaymentDueDate) {
        this.nextPaymentDueDate = nextPaymentDueDate;
    }

    public LocalDate getScheduleAnchorDate() {
        return scheduleAnchorDate;
    }

    public void setScheduleAnchorDate(LocalDate scheduleAnchorDate) {
        this.scheduleAnchorDate = scheduleAnchorDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
