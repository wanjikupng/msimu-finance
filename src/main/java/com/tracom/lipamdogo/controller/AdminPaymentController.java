package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.PaymentTransactionResponse;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
import com.tracom.lipamdogo.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Staff transaction visibility (operational view, not reconciliation).
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
@PreAuthorize("hasAnyRole('ADMIN','PAYMENTS_OPERATOR')")
public class AdminPaymentController {

    private final PaymentService paymentService;

    public AdminPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public ResponseEntity<List<PaymentTransactionResponse>> listTransactions(
            @RequestParam(required = false) PaymentTransactionStatus status,
            @RequestParam(required = false) Long contractId,
            @RequestParam(required = false) Long customerId) {
        return ResponseEntity.ok(paymentService.adminList(status, contractId, customerId));
    }
}
