package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.DeviceResponse;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Customer's own physical devices. Scoped strictly to the caller's account:
 * another customer's device is indistinguishable from a missing one (404).
 */
@RestController
@RequestMapping("/api/v1/customer/devices")
public class CustomerDeviceController {

    private final DeviceRepository deviceRepository;

    public CustomerDeviceController(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @GetMapping
    public ResponseEntity<List<DeviceResponse>> myDevices(@AuthenticationPrincipal UserPrincipal principal) {
        List<DeviceResponse> devices = deviceRepository.findAllByCustomerId(principal.getId()).stream()
                .map(DeviceResponse::from)
                .toList();
        return ResponseEntity.ok(devices);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceResponse> myDevice(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long id) {
        return deviceRepository.findByIdAndCustomerId(id, principal.getId())
                .map(DeviceResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));
    }
}
