package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.model.PaymentFrequency;
import com.tracom.lipamdogo.model.PaymentPlan;
import com.tracom.lipamdogo.model.Product;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Single authoritative home for financing arithmetic. Controllers display the
 * results; only this service (and the submission/approval paths that call it)
 * decides the terms, always in {@link BigDecimal}.
 *
 * <p>Prototype business rules (documented, deterministic, no credit scoring):</p>
 * <ul>
 *   <li>deposit must be non-negative and strictly below the device price;</li>
 *   <li>deposit must meet the product's minimum ({@code downPayment});</li>
 *   <li>financed principal = device price − deposit;</li>
 *   <li>installment = the plan's daily amount, cadence DAILY;</li>
 *   <li>total payable = principal (no interest or fees in this prototype).</li>
 * </ul>
 */
@Service
public class FinancingCalculationService {

    public record FinancingTerms(
            BigDecimal devicePrice,
            BigDecimal depositAmount,
            BigDecimal requestedPrincipal,
            BigDecimal installmentAmount,
            PaymentFrequency paymentFrequency,
            String paymentPlanName,
            Integer planDurationDays,
            BigDecimal totalPayable
    ) {
    }

    public FinancingTerms calculate(Product product, PaymentPlan plan, BigDecimal depositAmount) {
        if (depositAmount == null) {
            throw new IllegalArgumentException("Deposit is required.");
        }
        BigDecimal price = product.getFullPrice();
        if (depositAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Deposit cannot be negative.");
        }
        if (depositAmount.compareTo(price) >= 0) {
            throw new IllegalArgumentException("Deposit must be below the device price.");
        }
        if (depositAmount.compareTo(product.getDownPayment()) < 0) {
            throw new IllegalArgumentException(
                    "Deposit must meet the minimum required deposit of KES " + product.getDownPayment() + ".");
        }

        BigDecimal principal = price.subtract(depositAmount);
        return new FinancingTerms(
                price,
                depositAmount,
                principal,
                plan.getDailyAmount(),
                PaymentFrequency.DAILY,
                plan.getPlanName(),
                plan.getDurationDays(),
                principal
        );
    }
}
