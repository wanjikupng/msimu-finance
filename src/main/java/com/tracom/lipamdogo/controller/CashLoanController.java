package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.CashLoanRequest;
import com.tracom.lipamdogo.dto.CashLoanResponse;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CashLoanController {

    private final CustomerService customerService;

    public CashLoanController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping("/cash-loans")
    public ResponseEntity<CashLoanResponse> createCashLoan(@AuthenticationPrincipal UserPrincipal principal,
                                                           @Valid @RequestBody CashLoanRequest request) {
        return ResponseEntity.ok(customerService.createCashLoan(principal, request));
    }
}
