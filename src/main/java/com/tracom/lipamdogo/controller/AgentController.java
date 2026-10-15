package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.AgentResponse;
import com.tracom.lipamdogo.dto.DeviceResponse;
import com.tracom.lipamdogo.dto.MerchantResponse;
import com.tracom.lipamdogo.model.Agent;
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
 * Agent-scoped operations. Everything is resolved from the caller's own agent
 * profile; there is no merchant/agent id parameter to spoof.
 */
@RestController
@RequestMapping("/api/v1/agents")
@PreAuthorize("hasRole('AGENT')")
public class AgentController {

    private final AgentService agentService;
    private final MerchantService merchantService;
    private final DeviceService deviceService;
    private final DeviceRepository deviceRepository;

    public AgentController(AgentService agentService,
                           MerchantService merchantService,
                           DeviceService deviceService,
                           DeviceRepository deviceRepository) {
        this.agentService = agentService;
        this.merchantService = merchantService;
        this.deviceService = deviceService;
        this.deviceRepository = deviceRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<AgentResponse> myProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(agentService.profileForAccount(principal.getId()));
    }

    @GetMapping("/me/merchant")
    public ResponseEntity<MerchantResponse> myMerchant(@AuthenticationPrincipal UserPrincipal principal) {
        Agent agent = agentService.requireAgentForAccount(principal.getId());
        return ResponseEntity.ok(merchantService.getMerchant(agent.getMerchant().getId()));
    }

    @GetMapping("/me/devices")
    public ResponseEntity<List<DeviceResponse>> myDevices(@AuthenticationPrincipal UserPrincipal principal) {
        Agent agent = agentService.requireAgentForAccount(principal.getId());
        return ResponseEntity.ok(deviceService.listDevices(null, null, null, agent.getId(), null));
    }

    /**
     * One device, only if it sits with this agent. Anything else is 404, which
     * also keeps other merchants' inventory undiscoverable through this scope.
     */
    @GetMapping("/me/devices/{id}")
    public ResponseEntity<DeviceResponse> myDevice(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long id) {
        Agent agent = agentService.requireAgentForAccount(principal.getId());
        return deviceRepository.findByIdAndAgentId(id, agent.getId())
                .map(DeviceResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));
    }
}
