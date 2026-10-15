package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.PaymentAllocationResponse;
import com.tracom.lipamdogo.dto.PaymentInitiateRequest;
import com.tracom.lipamdogo.dto.PaymentTransactionResponse;
import com.tracom.lipamdogo.dto.ProviderResultRequest;
import com.tracom.lipamdogo.dto.ReceiptResponse;
import com.tracom.lipamdogo.dto.ReverseTransactionRequest;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.PaymentPostingService;
import com.tracom.lipamdogo.service.PaymentService;
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

import java.util.List;

/**
 * Payment transaction API. Customers initiate and observe; financial state
 * moves only through the engine (adapter outcomes, explicit posting,
 * controlled reversal). There is deliberately no generic status-edit endpoint.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentPostingService postingService;

    public PaymentController(PaymentService paymentService, PaymentPostingService postingService) {
        this.paymentService = paymentService;
        this.postingService = postingService;
    }

    @PostMapping
    public ResponseEntity<PaymentTransactionResponse> initiate(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody PaymentInitiateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaymentTransactionResponse response = paymentService.initiate(principal, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<List<PaymentTransactionResponse>> myTransactions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) PaymentTransactionStatus status) {
        return ResponseEntity.ok(paymentService.myTransactions(principal, status));
    }

    @GetMapping("/{reference}")
    public ResponseEntity<PaymentTransactionResponse> getTransaction(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String reference) {
        return ResponseEntity.ok(paymentService.getTransaction(principal, reference));
    }

    @GetMapping("/{reference}/receipt")
    public ResponseEntity<ReceiptResponse> getReceipt(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String reference) {
        PaymentTransactionResponse transaction = paymentService.getTransaction(principal, reference);
        // Receipts survive reversal as retrievable history.
        if (transaction.status() != PaymentTransactionStatus.POSTED
                && transaction.status() != PaymentTransactionStatus.REVERSED) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(postingService.receiptFor(reference));
    }

    @GetMapping("/{reference}/allocations")
    public ResponseEntity<List<PaymentAllocationResponse>> getAllocations(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String reference) {
        return ResponseEntity.ok(paymentService.getAllocations(principal, reference));
    }

    /**
     * Explicit posting of a deferred SUCCESS transaction. Customers can never
     * post: synchronous SUCCESS outcomes are posted by the engine itself at
     * initiation time, deferred ones by payments staff.
     */
    @PostMapping("/{reference}/post")
    @PreAuthorize("hasAnyRole('PAYMENTS_OPERATOR','ADMIN')")
    public ResponseEntity<ReceiptResponse> post(@PathVariable String reference) {
        return ResponseEntity.ok(postingService.post(reference));
    }

    /**
     * Simulation hook for deferred provider outcomes. ADMIN-only: a customer
     * must never be able to mark their own payment successful.
     */
    @PostMapping("/{reference}/provider-result")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentTransactionResponse> providerResult(
            @PathVariable String reference,
            @Valid @RequestBody ProviderResultRequest request) {
        return ResponseEntity.ok(paymentService.applyProviderResult(
                reference, request.outcome(), request.failureReason()));
    }

    @PostMapping("/{reference}/reverse")
    @PreAuthorize("hasAnyRole('PAYMENTS_OPERATOR','ADMIN')")
    public ResponseEntity<PaymentTransactionResponse> reverse(
            @PathVariable String reference,
            @Valid @RequestBody ReverseTransactionRequest request) {
        postingService.reverse(reference, request.reason());
        return ResponseEntity.ok(paymentService.adminGet(reference));
    }
}
