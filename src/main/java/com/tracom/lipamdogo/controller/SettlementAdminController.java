package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.BeneficiaryBalanceResponse;
import com.tracom.lipamdogo.dto.CommissionEntryResponse;
import com.tracom.lipamdogo.dto.CommissionPlanResponse;
import com.tracom.lipamdogo.dto.SettlementBatchResponse;
import com.tracom.lipamdogo.dto.SettlementDecisionRequest;
import com.tracom.lipamdogo.dto.SettlementRunRequest;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.CommissionService;
import com.tracom.lipamdogo.service.SettlementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * Commission ledger and settlement batches (maker-checker M-PESA payouts).
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('ADMIN','PAYMENTS_OPERATOR')")
public class SettlementAdminController {

    private final CommissionService commissionService;
    private final SettlementService settlementService;

    public SettlementAdminController(CommissionService commissionService, SettlementService settlementService) {
        this.commissionService = commissionService;
        this.settlementService = settlementService;
    }

    @GetMapping("/commissions/plan")
    public ResponseEntity<CommissionPlanResponse> plan() {
        return ResponseEntity.ok(commissionService.plan());
    }

    @GetMapping("/commissions")
    public ResponseEntity<List<CommissionEntryResponse>> ledger(@RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(commissionService.ledger(Math.max(1, Math.min(limit, 500))));
    }

    @GetMapping("/commissions/balances")
    public ResponseEntity<List<BeneficiaryBalanceResponse>> balances() {
        return ResponseEntity.ok(commissionService.balances());
    }

    @GetMapping("/settlements")
    public ResponseEntity<List<SettlementBatchResponse>> list() {
        return ResponseEntity.ok(settlementService.list());
    }

    @GetMapping("/settlements/{id}")
    public ResponseEntity<SettlementBatchResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(settlementService.get(id));
    }

    @PostMapping("/settlements")
    public ResponseEntity<SettlementBatchResponse> run(@AuthenticationPrincipal UserPrincipal principal,
                                                       @Valid @RequestBody SettlementRunRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settlementService.run(request.cutoffDate(), principal));
    }

    @PostMapping("/settlements/{id}/approve")
    public ResponseEntity<SettlementBatchResponse> approve(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long id,
                                                           @Valid @RequestBody(required = false) SettlementDecisionRequest request) {
        return ResponseEntity.ok(settlementService.approve(id, principal, request == null ? null : request.note()));
    }

    @PostMapping("/settlements/{id}/reject")
    public ResponseEntity<SettlementBatchResponse> reject(@AuthenticationPrincipal UserPrincipal principal,
                                                          @PathVariable Long id,
                                                          @Valid @RequestBody(required = false) SettlementDecisionRequest request) {
        return ResponseEntity.ok(settlementService.reject(id, principal, request == null ? null : request.note()));
    }
}
