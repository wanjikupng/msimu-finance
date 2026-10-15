package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.PaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentPlanRepository extends JpaRepository<PaymentPlan, Long> {
}
