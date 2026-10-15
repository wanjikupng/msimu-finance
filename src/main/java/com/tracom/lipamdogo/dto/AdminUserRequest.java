package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * ADMIN-only creation of an account with an explicit role (agent, merchant,
 * officer, …). Self-service registration stays CUSTOMER-only.
 */
public record AdminUserRequest(
        @NotBlank(message = "Full name is required.")
        @Size(max = 120, message = "Full name must be at most 120 characters.")
        String fullName,

        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be valid.")
        @Size(max = 160, message = "Email must be at most 160 characters.")
        String email,

        @Size(max = 32, message = "Phone number must be at most 32 characters.")
        String phoneNumber,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Password must contain at least one letter and one digit.")
        String password,

        @NotNull(message = "Role is required.")
        Role role
) {
}
