package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.ProviderFeedImportRequest;
import com.tracom.lipamdogo.dto.ProviderFeedImportResponse;
import com.tracom.lipamdogo.dto.ReconciliationBatchResponse;
import com.tracom.lipamdogo.dto.ReconciliationRecordResponse;
import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.ReconciliationStatus;
import com.tracom.lipamdogo.service.ReconciliationService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Provider-feed ingestion and reconciliation runs. Restricted to ADMIN and
 * PAYMENTS_OPERATOR: customers, agents, and merchants must never submit
 * provider confirmations or read other customers' payment data.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@PreAuthorize("hasAnyRole('ADMIN','PAYMENTS_OPERATOR')")
public class ReconciliationAdminController {

    private final ReconciliationService reconciliationService;

    public ReconciliationAdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/feed")
    public ResponseEntity<ProviderFeedImportResponse> importFeed(
            @Valid @RequestBody ProviderFeedImportRequest request) {
        return ResponseEntity.ok(reconciliationService.importFeed(request));
    }

    @PostMapping("/runs")
    public ResponseEntity<ReconciliationBatchResponse> run(
            @RequestParam(required = false) PaymentChannel provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime windowStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime windowEnd) {
        return ResponseEntity.ok(reconciliationService.run(provider, windowStart, windowEnd));
    }

    @GetMapping("/batches")
    public ResponseEntity<List<ReconciliationBatchResponse>> listBatches() {
        return ResponseEntity.ok(reconciliationService.listBatches());
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<ReconciliationBatchResponse> getBatch(@PathVariable Long id) {
        return ResponseEntity.ok(reconciliationService.getBatch(id));
    }

    @GetMapping("/records")
    public ResponseEntity<List<ReconciliationRecordResponse>> listRecords(
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) ReconciliationStatus status) {
        return ResponseEntity.ok(reconciliationService.listRecords(batchId, status));
    }
}
