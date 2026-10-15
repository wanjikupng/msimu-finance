package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.DashboardResponse;
import com.tracom.lipamdogo.dto.UserProfileResponse;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.CustomerService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final CustomerService customerService;

    public UserController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /**
     * Own profile. Identity comes from the token; no id parameter to spoof.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> myProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(customerService.getProfile(principal.getId()));
    }

    /**
     * Own dashboard. Identity comes from the token, which prevents IDOR/BOLA:
     * a customer can never address another customer's dashboard through this
     * endpoint.
     */
    @GetMapping("/me/dashboard")
    public ResponseEntity<DashboardResponse> myDashboard(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(customerService.getDashboard(principal.getId()));
    }

    /**
     * Administrative read of any customer's dashboard. Explicitly restricted to
     * staff roles; customers reaching this endpoint get 403.
     */
    @GetMapping("/{id}/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN','LOAN_OFFICER')")
    public ResponseEntity<DashboardResponse> dashboardById(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getDashboard(id));
    }
}
