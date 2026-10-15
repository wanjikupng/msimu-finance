package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.DeviceLockResponse;
import com.tracom.lipamdogo.dto.DeviceProvisioningResponse;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.DeviceLockService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pay-as-you-go device lock: lock status and unlock codes.
 */
@RestController
@RequestMapping("/api/v1")
public class DeviceLockController {

    private final DeviceLockService deviceLockService;

    public DeviceLockController(DeviceLockService deviceLockService) {
        this.deviceLockService = deviceLockService;
    }

    /** The contract holder's lock status and current unlock code (ownership enforced). */
    @GetMapping("/loans/{contractId}/device-lock")
    public ResponseEntity<DeviceLockResponse> forContract(@AuthenticationPrincipal UserPrincipal principal,
                                                          @PathVariable Long contractId) {
        return ResponseEntity.ok(deviceLockService.forCustomer(principal, contractId));
    }

    /** Staff view: lock status plus the full code history. */
    @GetMapping("/admin/devices/{deviceId}/lock")
    @PreAuthorize("hasAnyRole('ADMIN','LOAN_OFFICER','PAYMENTS_OPERATOR','TECHNICIAN')")
    public ResponseEntity<DeviceLockResponse> forDevice(@PathVariable Long deviceId) {
        return ResponseEntity.ok(deviceLockService.forStaff(deviceId));
    }

    /**
     * Hands out the device secret for loading into its lock agent (or the
     * phone simulator). POST because it discloses key material; logged.
     */
    @PostMapping("/admin/devices/{deviceId}/provisioning")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    public ResponseEntity<DeviceProvisioningResponse> provisioning(@PathVariable Long deviceId) {
        return ResponseEntity.ok(deviceLockService.provisioning(deviceId));
    }
}
