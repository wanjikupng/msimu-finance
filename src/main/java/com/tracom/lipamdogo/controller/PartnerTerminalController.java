package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.TerminalResponse;
import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.MerchantService;
import com.tracom.lipamdogo.service.TerminalService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Terminals visible to partners: an agent sees the terminal(s) they operate,
 * a merchant sees every terminal at their business.
 */
@RestController
@RequestMapping("/api/v1")
public class PartnerTerminalController {

    private final TerminalService terminalService;
    private final MerchantService merchantService;

    public PartnerTerminalController(TerminalService terminalService, MerchantService merchantService) {
        this.terminalService = terminalService;
        this.merchantService = merchantService;
    }

    @GetMapping("/agents/me/terminals")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<List<TerminalResponse>> agentTerminals(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(terminalService.forAgentUser(principal));
    }

    @GetMapping("/merchants/me/terminals")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<List<TerminalResponse>> merchantTerminals(@AuthenticationPrincipal UserPrincipal principal) {
        Merchant merchant = merchantService.requireMerchantForOwner(principal.getId());
        return ResponseEntity.ok(terminalService.forMerchant(merchant.getId()));
    }
}
