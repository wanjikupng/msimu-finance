package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.FinancingApplicationResponse;
import com.tracom.lipamdogo.dto.FinancingSummaryResponse;
import com.tracom.lipamdogo.dto.LoanContractResponse;
import com.tracom.lipamdogo.dto.RejectApplicationRequest;
import com.tracom.lipamdogo.model.ApplicationStatus;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.FinancingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Loan-officer review queue. The reviewer is always the authenticated
 * principal — no reviewer identity is accepted from the request body.
 */
@RestController
@RequestMapping("/api/v1/admin/financing/applications")
@PreAuthorize("hasAnyRole('ADMIN','LOAN_OFFICER')")
public class FinancingAdminController {

    private final FinancingService financingService;

    public FinancingAdminController(FinancingService financingService) {
        this.financingService = financingService;
    }

    @GetMapping
    public ResponseEntity<List<FinancingApplicationResponse>> listApplications(
            @RequestParam(required = false) ApplicationStatus status) {
        return ResponseEntity.ok(financingService.listApplications(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FinancingApplicationResponse> getApplication(@PathVariable Long id) {
        return ResponseEntity.ok(financingService.getApplication(id));
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<FinancingApplicationResponse> startReview(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(financingService.startReview(principal, id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<LoanContractResponse> approve(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(financingService.approveApplication(principal, id));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<FinancingApplicationResponse> reject(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody RejectApplicationRequest request) {
        return ResponseEntity.ok(
                financingService.rejectApplication(principal, id, request.decisionReason()));
    }

    @GetMapping("/summary")
    public ResponseEntity<FinancingSummaryResponse> summary() {
        return ResponseEntity.ok(financingService.summary());
    }
}
