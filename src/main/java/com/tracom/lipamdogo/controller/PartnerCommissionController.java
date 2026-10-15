package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.PartnerCommissionResponse;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.CommissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partners' own commission: an agent sees theirs, a merchant sees the
 * business's plus a per-agent breakdown.
 */
@RestController
@RequestMapping("/api/v1")
public class PartnerCommissionController {

    private final CommissionService commissionService;

    public PartnerCommissionController(CommissionService commissionService) {
        this.commissionService = commissionService;
    }

    @GetMapping("/agents/me/commissions")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<PartnerCommissionResponse> agent(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(commissionService.forAgent(principal));
    }

    @GetMapping("/merchants/me/commissions")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<PartnerCommissionResponse> merchant(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(commissionService.forMerchant(principal));
    }
}
