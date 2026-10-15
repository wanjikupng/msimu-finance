package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.AdminUserRequest;
import com.tracom.lipamdogo.dto.UserProfileResponse;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * ADMIN-only identity management (creating agent/merchant/officer accounts).
 * Self-service registration stays CUSTOMER-only in {@link AuthService}.
 */
@Service
public class AdminService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminService(UserAccountRepository userAccountRepository,
                        PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserProfileResponse createUser(AdminUserRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userAccountRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        UserAccount user = new UserAccount();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhoneNumber(request.phoneNumber() == null ? "" : request.phoneNumber().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(request.role() == null ? Role.CUSTOMER : request.role());
        user.setCustomerCode(generateCustomerCode());
        return UserProfileResponse.from(userAccountRepository.save(user));
    }

    @Transactional
    public List<UserProfileResponse> listUsers(Role role) {
        return userAccountRepository.findByRoleOrderByCreatedAtDesc(role == null ? Role.CUSTOMER : role)
                .stream()
                .map(UserProfileResponse::from)
                .toList();
    }

    private String generateCustomerCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "C-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            if (!userAccountRepository.existsByCustomerCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique customer code.");
    }
}
