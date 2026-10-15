package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.LoanContractResponse;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.LoanService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    /**
     * Staff-only ledger of every financing contract.
     */
    @GetMapping("/loans")
    @PreAuthorize("hasAnyRole('ADMIN','LOAN_OFFICER')")
    public ResponseEntity<List<LoanContractResponse>> getAllLoans() {
        return ResponseEntity.ok(loanService.getAllContracts());
    }

    /**
     * The caller's own contracts (multi-contract aware).
     */
    @GetMapping("/loans/me")
    public ResponseEntity<List<LoanContractResponse>> getMyLoans(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(loanService.getMyContracts(principal));
    }

    /**
     * One contract, with server-side ownership enforcement.
     */
    @GetMapping("/loans/{id}")
    public ResponseEntity<LoanContractResponse> getLoan(@AuthenticationPrincipal UserPrincipal principal,
                                                        @PathVariable Long id) {
        return ResponseEntity.ok(loanService.getContract(principal, id));
    }

    // NOTE: direct loan initialization was removed in Phase 3. Contracts are
    // created exclusively through the financing-application approval workflow
    // (FinancingService.approveApplication); no endpoint may bypass it.
}
