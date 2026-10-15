package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.AgentResponse;
import com.tracom.lipamdogo.dto.DeviceResponse;
import com.tracom.lipamdogo.dto.MerchantResponse;
import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.AgentService;
import com.tracom.lipamdogo.service.DeviceService;
import com.tracom.lipamdogo.service.MerchantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Merchant-scoped operations, resolved from the caller's linked merchant.
 * A merchant can never address another merchant's data through this scope.
 */
@RestController
@RequestMapping("/api/v1/merchants")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantController {

    private final MerchantService merchantService;
    private final AgentService agentService;
    private final DeviceService deviceService;
    private final DeviceRepository deviceRepository;

    public MerchantController(MerchantService merchantService,
                              AgentService agentService,
                              DeviceService deviceService,
                              DeviceRepository deviceRepository) {
        this.merchantService = merchantService;
        this.agentService = agentService;
        this.deviceService = deviceService;
        this.deviceRepository = deviceRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<MerchantResponse> myMerchant(@AuthenticationPrincipal UserPrincipal principal) {
        Merchant merchant = merchantService.requireMerchantForOwner(principal.getId());
        return ResponseEntity.ok(merchantService.getMerchant(merchant.getId()));
    }

    @GetMapping("/me/agents")
    public ResponseEntity<List<AgentResponse>> myAgents(@AuthenticationPrincipal UserPrincipal principal) {
        Merchant merchant = merchantService.requireMerchantForOwner(principal.getId());
        return ResponseEntity.ok(agentService.listAgents(merchant.getId()));
    }

    @GetMapping("/me/devices")
    public ResponseEntity<List<DeviceResponse>> myDevices(@AuthenticationPrincipal UserPrincipal principal) {
        Merchant merchant = merchantService.requireMerchantForOwner(principal.getId());
        return ResponseEntity.ok(deviceService.listDevices(null, null, merchant.getId(), null, null));
    }

    @GetMapping("/me/devices/{id}")
    public ResponseEntity<DeviceResponse> myDevice(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long id) {
        Merchant merchant = merchantService.requireMerchantForOwner(principal.getId());
        return deviceRepository.findByIdAndMerchantId(id, merchant.getId())
                .map(DeviceResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));
    }
}
