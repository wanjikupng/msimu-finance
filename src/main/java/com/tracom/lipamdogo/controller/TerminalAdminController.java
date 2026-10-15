package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.PaymentTransactionResponse;
import com.tracom.lipamdogo.dto.TerminalAssignRequest;
import com.tracom.lipamdogo.dto.TerminalRequest;
import com.tracom.lipamdogo.dto.TerminalResponse;
import com.tracom.lipamdogo.dto.TerminalStatusRequest;
import com.tracom.lipamdogo.service.TerminalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Terminal management console: the fleet of POS terminals, who operates
 * each one, whether it is online, and what it collected today.
 */
@RestController
@RequestMapping("/api/v1/admin/terminals")
@PreAuthorize("hasAnyRole('ADMIN','PAYMENTS_OPERATOR')")
public class TerminalAdminController {

    private final TerminalService terminalService;

    public TerminalAdminController(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    @GetMapping
    public ResponseEntity<List<TerminalResponse>> list() {
        return ResponseEntity.ok(terminalService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TerminalResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(terminalService.get(id));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<PaymentTransactionResponse>> transactions(@PathVariable Long id) {
        return ResponseEntity.ok(terminalService.recentTransactions(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TerminalResponse> register(@Valid @RequestBody TerminalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(terminalService.register(request));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TerminalResponse> assign(@PathVariable Long id,
                                                   @Valid @RequestBody TerminalAssignRequest request) {
        return ResponseEntity.ok(terminalService.assign(id, request));
    }

    @PostMapping("/{id}/unassign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TerminalResponse> unassign(@PathVariable Long id) {
        return ResponseEntity.ok(terminalService.unassign(id));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TerminalResponse> changeStatus(@PathVariable Long id,
                                                         @Valid @RequestBody TerminalStatusRequest request) {
        return ResponseEntity.ok(terminalService.changeStatus(id, request));
    }
}
