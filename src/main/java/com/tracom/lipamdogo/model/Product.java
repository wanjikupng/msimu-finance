package com.tracom.lipamdogo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "full_price", nullable = false)
    private BigDecimal fullPrice;

    @Column(name = "down_payment", nullable = false)
    private BigDecimal downPayment;

    @Column(name = "daily_amount", nullable = false)
    private BigDecimal dailyAmount;

    @Column(name = "payment_plan_name")
    private String paymentPlanName;

    @Column(name = "payment_mode")
    private String paymentMode;

    public Product() {
    }

    public Product(String name, String description, BigDecimal fullPrice, BigDecimal downPayment, BigDecimal dailyAmount, String paymentPlanName, String paymentMode) {
        this.name = name;
        this.description = description;
        this.fullPrice = fullPrice;
        this.downPayment = downPayment;
        this.dailyAmount = dailyAmount;
        this.paymentPlanName = paymentPlanName;
        this.paymentMode = paymentMode;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getFullPrice() {
        return fullPrice;
    }

    public void setFullPrice(BigDecimal fullPrice) {
        this.fullPrice = fullPrice;
    }

    public BigDecimal getDownPayment() {
        return downPayment;
    }

    public void setDownPayment(BigDecimal downPayment) {
        this.downPayment = downPayment;
    }

    public BigDecimal getDailyAmount() {
        return dailyAmount;
    }

    public void setDailyAmount(BigDecimal dailyAmount) {
        this.dailyAmount = dailyAmount;
    }

    public String getPaymentPlanName() {
        return paymentPlanName;
    }

    public void setPaymentPlanName(String paymentPlanName) {
        this.paymentPlanName = paymentPlanName;
    }

    public String getPaymentMode() {
        return paymentMode;
    }

    public void setPaymentMode(String paymentMode) {
        this.paymentMode = paymentMode;
    }
}
