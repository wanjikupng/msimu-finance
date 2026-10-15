package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.DeviceResponse;
import com.tracom.lipamdogo.dto.FinancingApplicationRequest;
import com.tracom.lipamdogo.dto.FinancingApplicationResponse;
import com.tracom.lipamdogo.dto.FinancingQuoteResponse;
import com.tracom.lipamdogo.model.DeviceStatus;
import com.tracom.lipamdogo.model.PaymentMode;
import com.tracom.lipamdogo.model.PaymentPlan;
import com.tracom.lipamdogo.repository.PaymentModeRepository;
import com.tracom.lipamdogo.repository.PaymentPlanRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.DeviceService;
import com.tracom.lipamdogo.service.FinancingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Customer-facing financing journey: browse eligible devices, preview
 * server-computed terms, submit and track applications. Identity always comes
 * from the token; the request carries no customer id.
 */
@RestController
@RequestMapping("/api/v1/financing")
public class FinancingController {

    private final FinancingService financingService;
    private final DeviceService deviceService;
    private final PaymentPlanRepository paymentPlanRepository;
    private final PaymentModeRepository paymentModeRepository;

    public FinancingController(FinancingService financingService,
                               DeviceService deviceService,
                               PaymentPlanRepository paymentPlanRepository,
                               PaymentModeRepository paymentModeRepository) {
        this.financingService = financingService;
        this.deviceService = deviceService;
        this.paymentPlanRepository = paymentPlanRepository;
        this.paymentModeRepository = paymentModeRepository;
    }

    @GetMapping("/eligible-devices")
    public ResponseEntity<List<DeviceResponse>> eligibleDevices() {
        return ResponseEntity.ok(deviceService.listDevices(DeviceStatus.AVAILABLE, null, null, null, null));
    }

    @GetMapping("/payment-plans")
    public ResponseEntity<List<PaymentPlan>> paymentPlans() {
        return ResponseEntity.ok(paymentPlanRepository.findAll());
    }

    @GetMapping("/payment-modes")
    public ResponseEntity<List<PaymentMode>> paymentModes() {
        return ResponseEntity.ok(paymentModeRepository.findAll());
    }

    @GetMapping("/quote")
    public ResponseEntity<FinancingQuoteResponse> quote(@RequestParam Long deviceId,
                                                        @RequestParam Long paymentPlanId,
                                                        @RequestParam BigDecimal deposit) {
        return ResponseEntity.ok(financingService.quote(deviceId, paymentPlanId, deposit));
    }

    @PostMapping("/applications")
    public ResponseEntity<FinancingApplicationResponse> submit(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody FinancingApplicationRequest request) {
        return ResponseEntity.ok(financingService.submitApplication(principal, request));
    }

    @GetMapping("/applications/me")
    public ResponseEntity<List<FinancingApplicationResponse>> myApplications(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(financingService.myApplications(principal));
    }

    @GetMapping("/applications/me/{id}")
    public ResponseEntity<FinancingApplicationResponse> myApplication(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(financingService.myApplication(principal, id));
    }

    @PostMapping("/applications/me/{id}/cancel")
    public ResponseEntity<FinancingApplicationResponse> cancelApplication(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(financingService.cancelApplication(principal, id));
    }
}
