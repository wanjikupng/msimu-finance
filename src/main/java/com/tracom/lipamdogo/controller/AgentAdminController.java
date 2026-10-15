package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.AgentRequest;
import com.tracom.lipamdogo.dto.AgentResponse;
import com.tracom.lipamdogo.dto.AgentUpdateRequest;
import com.tracom.lipamdogo.service.AgentService;
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
@RequestMapping("/api/v1/admin/agents")
@PreAuthorize("hasRole('ADMIN')")
public class AgentAdminController {

    private final AgentService agentService;

    public AgentAdminController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public ResponseEntity<AgentResponse> createAgent(@Valid @RequestBody AgentRequest request) {
        return ResponseEntity.ok(agentService.createAgent(request));
    }

    @GetMapping
    public ResponseEntity<List<AgentResponse>> listAgents(@RequestParam(required = false) Long merchantId) {
        return ResponseEntity.ok(agentService.listAgents(merchantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentResponse> getAgent(@PathVariable Long id) {
        return ResponseEntity.ok(agentService.getAgent(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AgentResponse> updateAgent(@PathVariable Long id,
                                                     @RequestBody AgentUpdateRequest request) {
        return ResponseEntity.ok(agentService.updateAgent(id, request));
    }
}
