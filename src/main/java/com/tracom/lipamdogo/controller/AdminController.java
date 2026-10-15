package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.PaymentModeRequest;
import com.tracom.lipamdogo.dto.PaymentPlanRequest;
import com.tracom.lipamdogo.model.PaymentMode;
import com.tracom.lipamdogo.model.PaymentPlan;
import com.tracom.lipamdogo.repository.PaymentModeRepository;
import com.tracom.lipamdogo.repository.PaymentPlanRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class AdminController {

    private final PaymentPlanRepository paymentPlanRepository;
    private final PaymentModeRepository paymentModeRepository;

    public AdminController(PaymentPlanRepository paymentPlanRepository, PaymentModeRepository paymentModeRepository) {
        this.paymentPlanRepository = paymentPlanRepository;
        this.paymentModeRepository = paymentModeRepository;
    }

    @GetMapping("/payment-plans")
    public List<PaymentPlan> getPaymentPlans() {
        return paymentPlanRepository.findAll();
    }

    @PostMapping("/payment-plans")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentPlan> createPaymentPlan(@RequestBody PaymentPlanRequest request) {
        PaymentPlan plan = new PaymentPlan();
        plan.setPlanName(request.planName());
        plan.setDurationDays(request.durationDays());
        plan.setDepositAmount(request.depositAmount());
        plan.setDailyAmount(request.dailyAmount());
        return ResponseEntity.ok(paymentPlanRepository.save(plan));
    }

    @GetMapping("/payment-modes")
    public List<PaymentMode> getPaymentModes() {
        return paymentModeRepository.findAll();
    }

    @PostMapping("/payment-modes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentMode> createPaymentMode(@RequestBody PaymentModeRequest request) {
        PaymentMode mode = new PaymentMode();
        mode.setModeName(request.modeName());
        mode.setDescription(request.description());
        return ResponseEntity.ok(paymentModeRepository.save(mode));
    }
}
