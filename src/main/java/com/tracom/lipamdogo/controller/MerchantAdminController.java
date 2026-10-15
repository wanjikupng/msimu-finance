package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.MerchantRequest;
import com.tracom.lipamdogo.dto.MerchantResponse;
import com.tracom.lipamdogo.dto.MerchantUpdateRequest;
import com.tracom.lipamdogo.model.MerchantStatus;
import com.tracom.lipamdogo.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/merchants")
@PreAuthorize("hasRole('ADMIN')")
public class MerchantAdminController {

    private final MerchantService merchantService;

    public MerchantAdminController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @PostMapping
    public ResponseEntity<MerchantResponse> createMerchant(@Valid @RequestBody MerchantRequest request) {
        return ResponseEntity.ok(merchantService.createMerchant(request));
    }

    @GetMapping
    public ResponseEntity<List<MerchantResponse>> listMerchants(
            @RequestParam(required = false) MerchantStatus status) {
        return ResponseEntity.ok(merchantService.listMerchants(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MerchantResponse> getMerchant(@PathVariable Long id) {
        return ResponseEntity.ok(merchantService.getMerchant(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<MerchantResponse> updateMerchant(@PathVariable Long id,
                                                           @RequestBody MerchantUpdateRequest request) {
        return ResponseEntity.ok(merchantService.updateMerchant(id, request));
    }
}
