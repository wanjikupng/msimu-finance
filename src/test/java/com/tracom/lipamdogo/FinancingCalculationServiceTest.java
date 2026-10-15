package com.tracom.lipamdogo;

import com.tracom.lipamdogo.model.PaymentPlan;
import com.tracom.lipamdogo.model.Product;
import com.tracom.lipamdogo.service.FinancingCalculationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The plan fixes the term; the installment follows from the financed amount,
 * so a "90 days" plan really finishes in 90 days.
 */
class FinancingCalculationServiceTest {

    private final FinancingCalculationService service = new FinancingCalculationService();

    private static Product galaxy() {
        return new Product("Samsung Galaxy A15", "test", new BigDecimal("18000"), new BigDecimal("4000"),
                new BigDecimal("70"), "Flex 200 Days", "M-PESA");
    }

    private static PaymentPlan plan(int days) {
        PaymentPlan plan = new PaymentPlan();
        plan.setPlanName(days + " days");
        plan.setDurationDays(days);
        plan.setDepositAmount(BigDecimal.ZERO);
        plan.setDailyAmount(BigDecimal.ZERO);
        return plan;
    }

    @Test
    void installmentIsPrincipalSpreadOverThePlanTerm() {
        var terms = service.calculate(galaxy(), plan(200), new BigDecimal("4000"));
        assertThat(terms.requestedPrincipal()).isEqualByComparingTo("14000");
        assertThat(terms.installmentAmount()).isEqualByComparingTo("70");
        assertThat(terms.totalPayable()).isEqualByComparingTo("14000");
    }

    @Test
    void installmentRoundsUpSoTheContractFinishesWithinTheTerm() {
        var terms = service.calculate(galaxy(), plan(90), new BigDecimal("4000"));
        // 14000 / 90 = 155.56 → 156 per day; 89 full days + a 116 remainder = day 90.
        assertThat(terms.installmentAmount()).isEqualByComparingTo("156");
        int days = terms.totalPayable().divide(terms.installmentAmount(), 0, java.math.RoundingMode.CEILING).intValue();
        assertThat(days).isEqualTo(90);
    }

    @Test
    void largerDepositLowersTheDailyInstallment() {
        var minimum = service.calculate(galaxy(), plan(120), new BigDecimal("4000"));
        var larger = service.calculate(galaxy(), plan(120), new BigDecimal("8000"));
        assertThat(larger.installmentAmount()).isLessThan(minimum.installmentAmount());
    }

    @Test
    void planWithoutDurationIsRejected() {
        PaymentPlan broken = plan(1);
        broken.setDurationDays(0);
        assertThatThrownBy(() -> service.calculate(galaxy(), broken, new BigDecimal("4000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
