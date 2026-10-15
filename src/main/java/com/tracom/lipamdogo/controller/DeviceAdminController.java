package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.DeviceAssignRequest;
import com.tracom.lipamdogo.dto.DeviceRequest;
import com.tracom.lipamdogo.dto.DeviceResponse;
import com.tracom.lipamdogo.dto.DeviceStatusRequest;
import com.tracom.lipamdogo.model.DeviceStatus;
import com.tracom.lipamdogo.service.DeviceService;
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
@RequestMapping("/api/v1/admin/devices")
@PreAuthorize("hasRole('ADMIN')")
public class DeviceAdminController {

    private final DeviceService deviceService;

    public DeviceAdminController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping
    public ResponseEntity<DeviceResponse> createDevice(@Valid @RequestBody DeviceRequest request) {
        return ResponseEntity.ok(deviceService.createDevice(request));
    }

    @GetMapping
    public ResponseEntity<List<DeviceResponse>> listDevices(
            @RequestParam(required = false) DeviceStatus status,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) Long customerId) {
        return ResponseEntity.ok(deviceService.listDevices(status, productId, merchantId, agentId, customerId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceResponse> getDevice(@PathVariable Long id) {
        return ResponseEntity.ok(deviceService.getDevice(id));
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<DeviceResponse> assignDevice(@PathVariable Long id,
                                                       @RequestBody DeviceAssignRequest request) {
        return ResponseEntity.ok(deviceService.assignDevice(id, request));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<DeviceResponse> releaseDevice(@PathVariable Long id) {
        return ResponseEntity.ok(deviceService.releaseDevice(id));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<DeviceResponse> changeStatus(@PathVariable Long id,
                                                       @Valid @RequestBody DeviceStatusRequest request) {
        return ResponseEntity.ok(deviceService.changeStatus(id, request));
    }
}
