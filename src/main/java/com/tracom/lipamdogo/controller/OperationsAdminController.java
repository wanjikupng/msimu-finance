package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.ArrearsSweepResponse;
import com.tracom.lipamdogo.service.ArrearsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Back-office batch operations that normally run on a schedule but can be
 * triggered by staff (e.g. re-running the end-of-day arrears sweep).
 */
@RestController
@RequestMapping("/api/v1/admin/operations")
@PreAuthorize("hasAnyRole('ADMIN','LOAN_OFFICER','PAYMENTS_OPERATOR')")
public class OperationsAdminController {

    private final ArrearsService arrearsService;

    public OperationsAdminController(ArrearsService arrearsService) {
        this.arrearsService = arrearsService;
    }

    @PostMapping("/arrears-sweep")
    public ResponseEntity<ArrearsSweepResponse> runArrearsSweep() {
        return ResponseEntity.ok(arrearsService.runSweep());
    }
}
