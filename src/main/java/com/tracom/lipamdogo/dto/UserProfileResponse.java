package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;

import java.time.LocalDateTime;

/**
 * Public profile projection of {@link UserAccount}. The password hash and any
 * security internals are deliberately absent.
 */
public record UserProfileResponse(
        Long id,
        String customerCode,
        String fullName,
        String email,
        String phoneNumber,
        Role role,
        LocalDateTime createdAt
) {
    public static UserProfileResponse from(UserAccount account) {
        return new UserProfileResponse(
                account.getId(),
                account.getCustomerCode(),
                account.getFullName(),
                account.getEmail(),
                account.getPhoneNumber(),
                account.getRole(),
                account.getCreatedAt()
        );
    }
}
