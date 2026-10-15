package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.PaymentTransactionResponse;
import com.tracom.lipamdogo.dto.PosCollectRequest;
import com.tracom.lipamdogo.dto.PosCustomerResponse;
import com.tracom.lipamdogo.dto.PosDaySummaryResponse;
import com.tracom.lipamdogo.dto.PosReceiptResponse;
import com.tracom.lipamdogo.dto.PosSessionResponse;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.PosService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The POS terminal app's API. Only the agent assigned to the terminal can use
 * it, and only while the terminal, the agent and the merchant are ACTIVE.
 * Cashless: collections are M-PESA payment prompts to the customer's phone.
 */
@RestController
@RequestMapping("/api/v1/pos/{terminalCode}")
@PreAuthorize("hasRole('AGENT')")
public class PosController {

    private final PosService posService;

    public PosController(PosService posService) {
        this.posService = posService;
    }

    @GetMapping("/session")
    public ResponseEntity<PosSessionResponse> session(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable String terminalCode,
                                                      @RequestHeader(value = "X-Terminal-App-Version", required = false) String appVersion) {
        return ResponseEntity.ok(posService.session(terminalCode, principal, appVersion));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable String terminalCode,
                                          @RequestHeader(value = "X-Terminal-App-Version", required = false) String appVersion) {
        posService.heartbeat(terminalCode, principal, appVersion);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/customers")
    public ResponseEntity<PosCustomerResponse> lookup(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable String terminalCode,
                                                      @RequestParam String query) {
        return ResponseEntity.ok(posService.lookup(terminalCode, principal, query));
    }

    @PostMapping("/collections")
    public ResponseEntity<PaymentTransactionResponse> collect(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String terminalCode,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PosCollectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(posService.collect(terminalCode, principal, request, idempotencyKey));
    }

    @GetMapping("/collections/{reference}")
    public ResponseEntity<PaymentTransactionResponse> status(@AuthenticationPrincipal UserPrincipal principal,
                                                             @PathVariable String terminalCode,
                                                             @PathVariable String reference) {
        return ResponseEntity.ok(posService.status(terminalCode, principal, reference));
    }

    /** Prototype only: plays the customer's phone answering the M-PESA prompt. */
    @PostMapping("/collections/{reference}/customer-response")
    public ResponseEntity<PaymentTransactionResponse> customerResponse(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String terminalCode,
            @PathVariable String reference,
            @RequestBody Map<String, Boolean> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return ResponseEntity.ok(posService.simulateCustomerResponse(terminalCode, principal, reference, approved));
    }

    @GetMapping("/collections/{reference}/receipt")
    public ResponseEntity<PosReceiptResponse> receipt(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable String terminalCode,
                                                      @PathVariable String reference) {
        return ResponseEntity.ok(posService.receipt(terminalCode, principal, reference));
    }

    @GetMapping("/day-summary")
    public ResponseEntity<PosDaySummaryResponse> daySummary(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable String terminalCode) {
        return ResponseEntity.ok(posService.daySummary(terminalCode, principal));
    }
}
